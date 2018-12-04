/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan.generational.immix;

import static org.mmtk.policy.immix.ImmixConstants.MARK_LINE_AT_SCAN_TIME;

import org.mmtk.plan.generational.GenCollector;
import org.mmtk.plan.generational.GenMatureTraceLocal;
import org.mmtk.plan.Trace;
import org.mmtk.policy.Space;
import org.mmtk.utility.Log;
import org.mmtk.utility.options.Options;
import org.mmtk.vm.VM;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This class implements the core functionality for a transitive
 * closure over the heap graph, specifically in a defragmenting pass over
 * a generational immix collector.
 */
@Uninterruptible
public final class GenImmixMatureDefragTraceLocal extends GenMatureTraceLocal{

  /**
   * @param global the global trace class to use
   * @param plan the state of the generational collector
   */
  public GenImmixMatureDefragTraceLocal(Trace global, GenCollector plan) {
    super(-1, global, plan);
  }

  @Override
  public boolean isLive(ObjectReference object) {
//    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(GenImmix.immixSpace.inImmixDefragCollection());
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(GenImmix.immixDramSpace.inImmixDefragCollection());
    if (object.isNull()) return false;
//    if (Space.isInSpace(GenImmix.IMMIX, object)) {
//      return GenImmix.immixSpace.isLive(object);
//    }

    if (Space.isInSpace(GenImmix.IMMIX_DRAM, object)) {
      return GenImmix.immixDramSpace.isLive(object);
    }
    if (Space.isInSpace(GenImmix.IMMIX_NVM, object)) {
      return GenImmix.immixNvmSpace.isLive(object);
    }
    return super.isLive(object);
  }

  @Override
  @Inline
  public ObjectReference traceObject(ObjectReference object) {
//    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(GenImmix.immixSpace.inImmixDefragCollection());
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(GenImmix.immixDramSpace.inImmixDefragCollection());
    if (object.isNull()) return object;
//    if (Space.isInSpace(GenImmix.IMMIX, object))
//      return GenImmix.immixSpace.traceObject(this, object, GenImmix.ALLOC_MATURE_MAJORGC);
    if (Space.isInSpace(GenImmix.IMMIX_DRAM, object))
      return GenImmix.immixDramSpace.traceObject(this, object, GenImmix.ALLOC_MATURE_MAJORGC);
    if (Space.isInSpace(GenImmix.IMMIX_NVM, object))
      return GenImmix.immixNvmSpace.traceObject(this, object, GenImmix.ALLOC_MATURE_MAJORGC);
    return super.traceObject(object);
  }

  @Override
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
//    if (Space.isInSpace(GenImmix.IMMIX, object)) {
//      return GenImmix.immixSpace.willNotMoveThisGC(object);
//    }
    if (Space.isInSpace(GenImmix.IMMIX_DRAM, object)) {
      return GenImmix.immixDramSpace.willNotMoveThisGC(object);
    }
    if (Space.isInSpace(GenImmix.IMMIX_NVM, object)) {
      return GenImmix.immixNvmSpace.willNotMoveThisGC(object);
    }
    return super.willNotMoveInCurrentCollection(object);
  }

  @Inline
  @Override
  protected void scanObject(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
      Log.write("SO[");
      Log.write(object);
      Log.writeln("]");
    }
    super.scanObject(object);
//    if (MARK_LINE_AT_SCAN_TIME && Space.isInSpace(GenImmix.IMMIX, object))
//      GenImmix.immixSpace.markLines(object);
    if (MARK_LINE_AT_SCAN_TIME) {
      if (Space.isInSpace(GenImmix.IMMIX_DRAM, object))
        GenImmix.immixDramSpace.markLines(object);
      if (Space.isInSpace(GenImmix.IMMIX_NVM, object))
        GenImmix.immixNvmSpace.markLines(object);
    }
  }
}
