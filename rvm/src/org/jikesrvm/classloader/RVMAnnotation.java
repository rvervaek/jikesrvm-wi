/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.classloader;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.Arrays;
import org.jikesrvm.VM;
import org.jikesrvm.runtime.Reflection;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.runtime.Statics;
import org.jikesrvm.util.ImmutableEntryHashMapRVM;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Offset;

/**
 * Internal representation of an annotation. We synthetically create
 * actual annotations {@link RVMClass}.
 */
public final class RVMAnnotation {
  /**
   * The type of the annotation. This is an interface name that the
   * annotation value will implement
   */
  private final TypeReference type;
  /**
   * Members of this annotation
   */
  private final AnnotationMember[] elementValuePairs;
  /**
   * A reference to the constructor of the base annotation
   */
  private static final MethodReference baseAnnotationInitMethod;
  /**
   * Remembered unique annotations
   */
  private static final ImmutableEntryHashMapRVM<RVMAnnotation, RVMAnnotation>
    uniqueMap = new ImmutableEntryHashMapRVM<RVMAnnotation, RVMAnnotation>();

  /**
   * The concrete annotation represented by this RVMAnnotation
   */
  private Annotation value;

  /**
   * Class constructor
   */
  static {
    baseAnnotationInitMethod =
        MemberReference.findOrCreate(TypeReference.BaseAnnotation,
                                        Atom.findOrCreateAsciiAtom("<init>"),
                                        Atom.findOrCreateAsciiAtom("(Lorg/jikesrvm/classloader/RVMAnnotation;)V")).asMethodReference();
    if (baseAnnotationInitMethod == null) {
      throw new Error("Error creating reference to base annotation");
    }
  }

  /**
   * Construct a read annotation
   * @param type the name of the type this annotation's value will
   * implement
   * @param elementValuePairs values for the fields in the annotation
   * that override the defaults
   */
  private RVMAnnotation(TypeReference type, AnnotationMember[] elementValuePairs) {
    this.type = type;
    this.elementValuePairs = elementValuePairs;
  }

  /**
   * Read an annotation attribute from the class file
   *
   * @param constantPool from constant pool being loaded
   * @param input the data being read
   */
  static RVMAnnotation readAnnotation(int[] constantPool, DataInputStream input, ClassLoader classLoader)
      throws IOException, ClassNotFoundException {
    TypeReference type;
    // Read type
    int typeIndex = input.readUnsignedShort();
    type = TypeReference.findOrCreate(classLoader, RVMClass.getUtf(constantPool, typeIndex));
    // Read values
    int numAnnotationMembers = input.readUnsignedShort();
    AnnotationMember[] elementValuePairs = new AnnotationMember[numAnnotationMembers];
    for (int i = 0; i < numAnnotationMembers; i++) {
      elementValuePairs[i] = AnnotationMember.readAnnotationMember(type, constantPool, input, classLoader);
    }
    // Arrays.sort(elementValuePairs);
    RVMAnnotation result = new RVMAnnotation(type, elementValuePairs);
    RVMAnnotation unique = uniqueMap.get(result);
    if (unique != null) {
      return unique;
    } else {
      uniqueMap.put(result, result);
      return result;
    }
  }

  /**
   * Return the annotation represented by this RVMAnnotation. If this
   * is the first time this annotation has been accessed the subclass
   * of annotation this class represents needs creating.
   * @return the annotation represented
   */
  Annotation getValue() {
    if (value == null) {
      value = createValue();
    }
    return value;
  }

  /**
   * Create an instance of this type of annotation with the values
   * given in the members
   *
   * @return the created annotation
   */
  private Annotation createValue() {
    // Find the annotation then find its implementing class
    final RVMClass annotationInterface = type.resolve().asClass();
    annotationInterface.resolve();
    final RVMClass annotationClass = annotationInterface.getAnnotationClass();
    if (!annotationClass.isResolved()) {
      annotationClass.resolve();
    }
    if (VM.runningVM) {
      if (!annotationClass.isInitialized()) {
        RuntimeEntrypoints.initializeClassForDynamicLink(annotationClass);
      }
      // Construct an instance with default values
      Annotation annotationInstance = (Annotation) RuntimeEntrypoints.resolvedNewScalar(annotationClass);
      RVMMethod defaultConstructor = annotationClass.getConstructorMethods()[0];
      Reflection.invoke(defaultConstructor, annotationInstance, new RVMAnnotation[]{this});
      // Override default values with those given in the element value pairs
      RVMField[] annotationClassFields = annotationClass.getDeclaredFields();
      for (AnnotationMember evp : elementValuePairs) {
        Atom evpFieldName = evp.getNameAsFieldName();
        for (RVMField field : annotationClassFields) {
          if (field.getName() == evpFieldName) {
            evp.setValueToField(field, annotationInstance);
          }
        }
      }
      return annotationInstance;
    } else {
      Class<?> interfaceClass = annotationInterface.getClassForType();
      return (Annotation) Proxy.newProxyInstance(interfaceClass
          .getClassLoader(), new Class[] { interfaceClass },
          new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
              for (AnnotationMember evp : elementValuePairs) {
                String evpFieldName = evp.getName().toString();
                if (method.getName().equals(evpFieldName)) {
                  return evp.getValue();
                }
              }
              if (method.getName().equals("hashCode")) {
                return hashCode();
              }
              if (method.getName().equals("equals")) {
                return equals(args[0]);
              }
              MethodReference methRef = MemberReference.findOrCreate(
                  annotationInterface.getTypeRef(),
                  Atom.findOrCreateAsciiAtom(method.getName()),
                  Atom.findOrCreateAsciiAtom("()" +
                                                TypeReference.findOrCreate(method.getReturnType())
                                                .getName())).asMethodReference();
              return methRef.resolve().getAnnotationDefault();
              //throw new Error("Annotation value not found for: " + method);
            }
          });
    }
  }

  /**
   * Return a string representation of the annotation of the form
   * "@type(name1=val1, ...nameN=valN)"
   */
  public String toString() {
    String result = type.toString();
    result = "@" + result.substring(1, result.length() - 1) + "(";
    if (elementValuePairs != null) {
      for (int i = 0; i < elementValuePairs.length; i++) {
        result += elementValuePairs[i];
        if (i < (elementValuePairs.length - 1)) {
          result += ", ";
        }
      }
    }
    result += ")";
    return result;
  }

  /**
   * Read the element_value field of an annotation
   *
   * @param constantPool the constant pool for the class being read
   * @param input stream to read from
   * @return object representing the value read
   */
  static <T> Object readValue(TypeReference type, int[] constantPool, DataInputStream input, ClassLoader classLoader)
      throws IOException, ClassNotFoundException {
    // Read element value's tag
    byte elementValue_tag = input.readByte();
    return readValue(type, constantPool, input, classLoader, elementValue_tag);
  }
  private static <T> Object readValue(TypeReference type, int[] constantPool, DataInputStream input, ClassLoader classLoader, byte elementValue_tag)
      throws IOException, ClassNotFoundException {
    // decode
    Object value;
    switch (elementValue_tag) {
      case'B': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.Byte);
        Offset offset = RVMClass.getLiteralOffset(constantPool, input.readUnsignedShort());
        value = (byte) Statics.getSlotContentsAsInt(offset);
        break;
      }
      case'C': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.Char);
        Offset offset = RVMClass.getLiteralOffset(constantPool, input.readUnsignedShort());
        value = (char) Statics.getSlotContentsAsInt(offset);
        break;
      }
      case'D': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.Double);
        Offset offset = RVMClass.getLiteralOffset(constantPool, input.readUnsignedShort());
        long longValue = Statics.getSlotContentsAsLong(offset);
        value = Double.longBitsToDouble(longValue);
        break;
      }
      case'F': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.Float);
        Offset offset = RVMClass.getLiteralOffset(constantPool, input.readUnsignedShort());
        int intValue = Statics.getSlotContentsAsInt(offset);
        value = Float.intBitsToFloat(intValue);
        break;
      }
      case'I': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.Int);
        Offset offset = RVMClass.getLiteralOffset(constantPool, input.readUnsignedShort());
        value = Statics.getSlotContentsAsInt(offset);
        break;
      }
      case'J': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.Long);
        Offset offset = RVMClass.getLiteralOffset(constantPool, input.readUnsignedShort());
        value = Statics.getSlotContentsAsLong(offset);
        break;
      }
      case'S': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.Short);
        Offset offset = RVMClass.getLiteralOffset(constantPool, input.readUnsignedShort());
        value = (short) Statics.getSlotContentsAsInt(offset);
        break;
      }
      case'Z': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.Boolean);
        Offset offset = RVMClass.getLiteralOffset(constantPool, input.readUnsignedShort());
        value = Statics.getSlotContentsAsInt(offset) == 1;
        break;
      }
      case's': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.JavaLangString);
        value = RVMClass.getUtf(constantPool, input.readUnsignedShort()).toString();
        break;
      }
      case'e': {
        int typeNameIndex = input.readUnsignedShort();
        @SuppressWarnings("unchecked") Class enumType =
            TypeReference.findOrCreate(classLoader,
                                          RVMClass.getUtf(constantPool, typeNameIndex)).resolve().getClassForType();
        int constNameIndex = input.readUnsignedShort();

        //noinspection unchecked
        value = Enum.valueOf(enumType, RVMClass.getUtf(constantPool, constNameIndex).toString());
        break;
      }
      case'c': {
        if(VM.VerifyAssertions) VM._assert(type == null || type == TypeReference.JavaLangClass);
        int classInfoIndex = input.readUnsignedShort();
        value = Class.forName(RVMClass.getUtf(constantPool, classInfoIndex).toString());
        break;
      }
      case'@':
        value = RVMAnnotation.readAnnotation(constantPool, input, classLoader);
        break;
      case'[': {
        int numValues = input.readUnsignedShort();
        if (numValues == 0) {
          if (type != null) {
            value = Array.newInstance(type.resolve().getClassForType(), 0);
          } else {
            value = new Object[0];
          }
        } else {
          byte innerElementValue_tag = input.readByte();
          TypeReference innerType = type == null ? null : type.getArrayElementType();
          switch(innerElementValue_tag) {
          case 'B': {
            byte[] array = new byte[numValues];
            array[0] = (Byte)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            for (int i = 1; i < numValues; i++) {
              array[i] = (Byte)readValue(innerType, constantPool, input, classLoader);
            }
            value = array;
            break;
          }
          case 'C': {
            char[] array = new char[numValues];
            array[0] = (Character)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            for (int i = 1; i < numValues; i++) {
              array[i] = (Character)readValue(innerType, constantPool, input, classLoader);
            }
            value = array;
            break;
          }
          case 'D': {
            double[] array = new double[numValues];
            array[0] = (Double)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            for (int i = 1; i < numValues; i++) {
              array[i] = (Double)readValue(innerType, constantPool, input, classLoader);
            }
            value = array;
            break;
          }
          case 'F': {
            float[] array = new float[numValues];
            array[0] = (Float)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            for (int i = 1; i < numValues; i++) {
              array[i] = (Float)readValue(innerType, constantPool, input, classLoader);
            }
            value = array;
            break;
          }
          case 'I': {
            int[] array = new int[numValues];
            array[0] = (Integer)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            for (int i = 1; i < numValues; i++) {
              array[i] = (Integer)readValue(innerType, constantPool, input, classLoader);
            }
            value = array;
            break;
          }
          case 'J': {
            long[] array = new long[numValues];
            array[0] = (Long)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            for (int i = 1; i < numValues; i++) {
              array[i] = (Long)readValue(innerType, constantPool, input, classLoader);
            }
            value = array;
            break;
          }
          case 'S': {
            short[] array = new short[numValues];
            array[0] = (Short)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            for (int i = 1; i < numValues; i++) {
              array[i] = (Short)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            }
            value = array;
            break;
          }
          case 'Z': {
            boolean[] array = new boolean[numValues];
            array[0] = (Boolean)readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            for (int i = 1; i < numValues; i++) {
              array[i] = (Boolean)readValue(innerType, constantPool, input, classLoader);
            }
            value = array;
            break;
          }
          case 's':
          case 'c':
          case '@':
          case 'e':
          case '[':
            Object value1 = readValue(innerType, constantPool, input, classLoader, innerElementValue_tag);
            value = Array.newInstance(value1.getClass(), numValues);
            Array.set(value, 0, value1);
            for (int i = 1; i < numValues; i++) {
              Array.set(value, i, readValue(innerType, constantPool, input, classLoader));
            }
            break;
          default:
            throw new ClassFormatError("Unknown element_value tag '" + (char) innerElementValue_tag + "'");
          }
        }
        break;
      }
      default:
        throw new ClassFormatError("Unknown element_value tag '" + (char) elementValue_tag + "'");
    }
    return value;
  }

  /**
   * Return the TypeReference of the declared annotation, ie an
   * interface and not the class object of this instance
   *
   * @return TypeReferernce of interface annotation object implements
   */
  @Uninterruptible
  TypeReference annotationType() { return type; }

  /**
   * Are two annotations logically equivalent?
   *
   * TODO: for performance reasons if we dynamically generated the
   * bytecode for this method, rather than using reflection, the
   * performance should be better.
   */
  static boolean equals(BaseAnnotation a, RVMAnnotation vmA, BaseAnnotation b, RVMAnnotation vmB) {
    if (vmA.type != vmB.type) {
      return false;
    } else {
      RVMClass annotationInterface = vmA.type.resolve().asClass();
      RVMClass annotationClass = annotationInterface.getAnnotationClass();
      RVMField[] annotationClassFields = annotationClass.getDeclaredFields();
      for (RVMField annotationClassField : annotationClassFields) {
        Object objA = annotationClassField.getObjectUnchecked(a);
        Object objB = annotationClassField.getObjectUnchecked(b);
        if (!objA.getClass().isArray()) {
          if (!objA.equals(objB)) {
            return false;
          }
        } else {
          return Arrays.equals((Object[]) objA, (Object[]) objB);
        }
      }
      return true;
    }
  }

  /**
   * Compute the hashCode for an instance of an annotation
   *
   * TODO: for performance reasons if we dynamically generated the
   * bytecode for this method, rather than using reflection, the
   * performance should be better.
   */
  public int hashCode(BaseAnnotation a) {
    RVMClass annotationInterface = type.resolve().asClass();
    RVMClass annotationClass = annotationInterface.getAnnotationClass();
    RVMField[] annotationClassFields = annotationClass.getDeclaredFields();
    String typeString = type.toString();
    int result = typeString.substring(1, typeString.length() - 1).hashCode();
    try {
      for (RVMField field : annotationClassFields) {
        String name = field.getName().toUnicodeString();
        name = name.substring(0, name.length() - 6); // remove "_field" from name
        Object value = field.getObjectUnchecked(a);
        int part_result = name.hashCode() * 127;
        if (value.getClass().isArray()) {
          part_result ^= Arrays.hashCode((Object[]) value);
        } else {
          part_result ^= value.hashCode();
        }
        result += part_result;
      }
    } catch (java.io.UTFDataFormatException e) {
      throw new Error(e);
    }
    return result;
  }

  /*
   * Hash map support
   */
  public int hashCode() {
    return type.hashCode();
  }

  public boolean equals(Object o) {
    if (o instanceof RVMAnnotation) {
      RVMAnnotation that = (RVMAnnotation)o;
      if (type == that.type) {
        if (elementValuePairs.length != that.elementValuePairs.length) {
          return false;
        }
        for (int i=0; i<elementValuePairs.length; i++) {
          if (!elementValuePairs[i].equals(that.elementValuePairs[i])) {
            return false;
          }
        }
        return true;
      } else {
        return false;
      }
    } else {
      return false;
    }
  }

  /**
   * @return member reference to init method of BaseAnnotation
   */
  static MethodReference getBaseAnnotationInitMemberReference() {
    if (baseAnnotationInitMethod == null) {
      throw new Error("Error creating reference to base annotation");
    }
    return baseAnnotationInitMethod;
  }

  /**
   * The superclass for all annotation instances
   */
  abstract static class BaseAnnotation implements Annotation {
    /**
     * The RVMAnnotation that this annotation is an instance of
     */
    private final RVMAnnotation vmAnnotation;

    /**
     * Constructor, called via RVMAnnotation.createValue
     */
    BaseAnnotation(RVMAnnotation vmAnnotation) {
      this.vmAnnotation = vmAnnotation;
    }

    /**
     * Return a string representation of the annotation of the form
     * "@type(name1=val1, ...nameN=valN)"
     */
    public String toString() {
      return vmAnnotation.toString();
    }

    /**
     * Return the Class object of the declared annotation, ie an
     * interface and not the class object of this instance
     *
     * @return Class object of interface annotation object implements
     */
    @SuppressWarnings("unchecked")
    // We intentionally break type-safety
    public Class<? extends Annotation> annotationType() {
      return (Class<? extends Annotation>) vmAnnotation.annotationType().resolve().getClassForType();
    }

    /**
     * Are two annotations logically equivalent?
     */
    public boolean equals(Object o) {
      if (o instanceof BaseAnnotation) {
        if (o == this) {
          return true;
        } else {
          BaseAnnotation b = (BaseAnnotation) o;
          return RVMAnnotation.equals(this, this.vmAnnotation, b, b.vmAnnotation);
        }
      } else {
        return false;
      }
    }

    /**
     * Compute the hash code of an annotation using the standard
     * algorithm {@link java.lang.annotation.Annotation#hashCode()}
     */
    public int hashCode() {
      return vmAnnotation.hashCode(this);
    }
  }

  /**
   * A class to decode and hold the name and its associated value for
   * an annotation member
   */
  private static final class AnnotationMember implements Comparable<AnnotationMember> {
    /**
     * Name of element
     */
    private final MethodReference meth;
    /**
     * Elements value, decoded from its tag
     */
    private final Object value;

    /**
     * Construct a read value pair
     */
    private AnnotationMember(MethodReference meth, Object value) {
      this.meth = meth;
      this.value = value;
    }

    /**
     * Read the pair from the input stream and create object
     * @param constantPool the constant pool for the class being read
     * @param input stream to read from
     * @param classLoader the class loader being used to load this annotation
     * @return a newly created annotation member
     */
    static AnnotationMember readAnnotationMember(TypeReference type, int[] constantPool, DataInputStream input, ClassLoader classLoader)
        throws IOException, ClassNotFoundException {
      // Read name of pair
      int elemNameIndex = input.readUnsignedShort();
      Atom name = RVMClass.getUtf(constantPool, elemNameIndex);
      MethodReference meth;
      Object value;
      if (type.isResolved()) {
        meth = type.resolve().asClass().findDeclaredMethod(name).getMemberRef().asMethodReference();
        value = RVMAnnotation.readValue(meth.getReturnType(), constantPool, input, classLoader);
      } else {
        value = RVMAnnotation.readValue(null, constantPool, input, classLoader);
        meth = MemberReference.findOrCreate(type, name,
            Atom.findOrCreateAsciiAtom("()"+TypeReference.findOrCreate(value.getClass()).getName())
        ).asMethodReference();
      }
      return new AnnotationMember(meth, value);
    }

    /**
     * Return name as it would appear in a class implementing this
     * annotation
     */
    Atom getNameAsFieldName() {
      // TODO: optimize this atom operation
      return Atom.findAsciiAtom(meth.getName().toString() + "_field");
    }

    /**
     * Set the value to the given field of the given annotation
     */
    void setValueToField(RVMField field, Annotation annotation) {
      if (value instanceof Boolean) {
        field.setBooleanValueUnchecked(annotation, (Boolean) value);
      } else if (value instanceof Integer) {
        field.setIntValueUnchecked(annotation, (Integer) value);
      } else if (value instanceof Long) {
        field.setLongValueUnchecked(annotation, (Long) value);
      } else if (value instanceof Byte) {
        field.setByteValueUnchecked(annotation, (Byte) value);
      } else if (value instanceof Character) {
        field.setCharValueUnchecked(annotation, (Character) value);
      } else if (value instanceof Short) {
        field.setShortValueUnchecked(annotation, (Short) value);
      } else if (value instanceof Float) {
        field.setFloatValueUnchecked(annotation, (Float) value);
      } else if (value instanceof Double) {
        field.setDoubleValueUnchecked(annotation, (Double) value);
      } else {
        field.setObjectValueUnchecked(annotation, value);
      }
    }
    /** @return the name of the of the given pair */
    Atom getName() {
      return meth.getName();
    }
    /** @return the value of the of the given pair */
    Object getValue() {
      return value;
    }
    /**
     * String representation of the value pair of the form
     * "name=value"
     */
    public String toString() {
      String result = getName().toString() + "=";
      if (value instanceof Object[]) {
        result += "{";
        Object[] a = (Object[]) value;
        for (int i = 0; i < a.length; i++) {
          result += a[i].toString();
          if (i < (a.length - 1)) {
            result += ", ";
          }
          result += "}";
        }
      } else {
        result += value.toString();
      }
      return result;
    }

    /**
     * Are two members equivalent?
     */
    public boolean equals(Object o) {
      if (o instanceof AnnotationMember) {
        AnnotationMember that = (AnnotationMember)o;
        return that.meth == meth && that.value.equals(value);
      } else {
        return false;
      }
    }

    /**
     * Compute hashCode from meth
     */
    public int hashCode() {
      return meth.hashCode();
    }

    /**
     * Ordering for sorted annotation members
     */
    public int compareTo(AnnotationMember am) {
      if (am.meth != this.meth) {
        return am.getName().toString().compareTo(this.getName().toString());
      } else {
        if (value.getClass().isArray()) {
          return Arrays.hashCode((Object[]) value) - Arrays.hashCode((Object[]) am.value);
        } else {
          @SuppressWarnings("unchecked") // True generic programming, we can't type check it in Java
              Comparable<Object> cValue = (Comparable) value;
          return cValue.compareTo(am.value);
        }
      }
    }
  }
}