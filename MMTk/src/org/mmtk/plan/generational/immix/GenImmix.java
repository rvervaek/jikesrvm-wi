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

import org.jikesrvm.VM;
import org.mmtk.plan.Plan;
import org.mmtk.plan.generational.Gen;
import org.mmtk.plan.Trace;
import org.mmtk.plan.TransitiveClosure;
import org.mmtk.policy.immix.ImmixSpace;
import org.mmtk.policy.immix.ObjectHeader;
import org.mmtk.policy.Space;
import org.mmtk.utility.Log;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.statistics.Stats;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class implements the functionality of a two-generation copying
 * collector where <b>the higher generation is an immix space</b>.
 *
 * Nursery collections occur when either the heap is full or the nursery
 * is full.  The nursery size is determined by an optional command line
 * argument. If undefined, the nursery size is "infinite", so nursery
 * collections only occur when the heap is full (this is known as a
 * flexible-sized nursery collector).  Thus both fixed and flexible
 * nursery sizes are supported.  Full heap collections occur when the
 * nursery size has dropped to a statically defined threshold,
 * <code>NURSERY_THRESHOLD</code><p>
 *
 * See the PLDI'08 paper by Blackburn and McKinley for a description
 * of the algorithm: http://doi.acm.org/10.1145/1375581.1375586<p>
 *
 * See the Jones &amp; Lins GC book, chapter 7 for a detailed discussion
 * of generational collection and section 7.3 for an overview of the
 * flexible nursery behavior ("The Standard ML of New Jersey
 * collector"), or go to Appel's paper "Simple generational garbage
 * collection and fast allocation." SP&amp;E 19(2):171--183, 1989.<p>
 *
 *
 * For general comments about the global/local distinction among classes refer
 * to Plan.java and PlanLocal.java.
 */
@Uninterruptible
public class GenImmix extends Gen {

  /*****************************************************************************
   *
   * Class fields
   */

  /** The mature space, which for GenImmix uses a mark sweep collection policy. */
//  public static final ImmixSpace immixSpace = new ImmixSpace("immix", false, VMRequest.discontiguous());
  public static final ImmixSpace immixDramSpace = new ImmixSpace("immixDram", false, VMRequest.discontiguous());
  public static final ImmixSpace immixNvmSpace = new ImmixSpace("immixNvm", false, VMRequest.discontiguous());

//  public static final int IMMIX = immixSpace.getDescriptor();
  public static final int IMMIX_DRAM = immixDramSpace.getDescriptor();
  public static final int IMMIX_NVM = immixNvmSpace.getDescriptor();

  /** Specialized scanning method identifier */
  public static final int SCAN_IMMIX = 1;

  /****************************************************************************
   *
   * Instance fields
   */

  /** The trace class for a full-heap collection */
  public final Trace matureTrace = new Trace(metaDataSpace);
  private boolean lastGCWasDefragDram = false;
  private boolean lastGCWasDefragNvm = false;

  /*****************************************************************************
   *
   * Collection
   */

  /**
   * {@inheritDoc}
   */
  @Inline
  @Override
  public final void collectionPhase(short phaseId) {
    if (phaseId == SET_COLLECTION_KIND) {
      super.collectionPhase(phaseId);
      if (gcFullHeap) {
//        immixSpace.decideWhetherToDefrag(emergencyCollection, true, collectionAttempt, userTriggeredCollection);
        immixDramSpace.decideWhetherToDefrag(emergencyCollection, true, collectionAttempt, userTriggeredCollection);
        immixNvmSpace.decideWhetherToDefrag(emergencyCollection, true, collectionAttempt, userTriggeredCollection);
      }
      return;
    }

    if (traceFullHeap()) {
      if (phaseId == PREPARE) {
        super.collectionPhase(phaseId);
        matureTrace.prepare();
//        immixSpace.prepare(true);
        immixDramSpace.prepare(true);
        immixNvmSpace.prepare(true);
        return;
      }

      if (phaseId == CLOSURE) {
        matureTrace.prepare();
        return;
      }

      if (phaseId == RELEASE) {
        matureTrace.release();
//        lastGCWasDefrag = immixSpace.release(true);
        lastGCWasDefragDram = immixDramSpace.release(true);
        lastGCWasDefragNvm = immixNvmSpace.release(true);
        super.collectionPhase(phaseId);
        return;
      }
    } else {
      lastGCWasDefragDram = false;
      lastGCWasDefragNvm = false;
    }

    super.collectionPhase(phaseId);
  }

  @Override
  public boolean lastCollectionWasExhaustive() {
    return lastGCWasDefragDram || lastGCWasDefragNvm;
  }

  /*****************************************************************************
   *
   * Accounting
   */

  /**
   * Return the number of pages reserved for use given the pending
   * allocation.
   */
  @Inline
  @Override
  public int getPagesUsed() {
//    return immixSpace.reservedPages() + super.getPagesUsed();
    return immixDramSpace.reservedPages() + immixNvmSpace.reservedPages() + super.getPagesUsed();
  }

  @Override
  public int getMaturePhysicalPagesAvail() {
    return immixDramSpace.availablePhysicalPages() + immixNvmSpace.availablePhysicalPages();
  }

  @Override
  public int getCollectionReserve() {
//    return super.getCollectionReserve() + immixSpace.defragHeadroomPages();
    return super.getCollectionReserve() + immixDramSpace.defragHeadroomPages() + immixNvmSpace.defragHeadroomPages();
  }

  /*****************************************************************************
   *
   * Miscellaneous
   */

  /**
   * @return The active mature space
   */
  @Override
  @Inline
  protected final Space activeMatureSpace() {
    return immixDramSpace; // method is never called, so no worries :)
  }

  @Override
  public boolean willNeverMove(ObjectReference object) {
    if (Space.isInSpace(IMMIX_DRAM, object) || Space.isInSpace(IMMIX_NVM, object)) {
//    if (Space.isInSpace(IMMIX, object)) {
      ObjectHeader.pinObject(object);
      return true;
    } else
      return super.willNeverMove(object);
  }

  @Override
  @Interruptible
  protected void registerSpecializedMethods() {
    TransitiveClosure.registerSpecializedScan(SCAN_IMMIX, GenImmixMatureTraceLocal.class);
//    TransitiveClosure.registerSpecializedScan(SCAN_DEFRAG, GenImmixMatureDefragTraceLocal.class);
    super.registerSpecializedMethods();
  }

  @Override
  @Interruptible
  public void preCollectorSpawn() {
//    immixSpace.initializeDefrag();
    immixDramSpace.initializeDefrag();
    immixNvmSpace.initializeDefrag();
  }

//  @Override
//  public void printPreStats() {
//    if(gcFullHeap && Stats.gatheringStats()) {
//      long current_dram_pre_old   = immixDramSpace.reservedPages();
//      long current_nvm_pre_old    = immixNvmSpace.reservedPages();
//      long current_dram_pre_large = Plan.loDramSpace.reservedPages();
//      long current_nvm_pre_large = Plan.loNvmSpace.reservedPages();
//
//      long pagesDramPre = current_dram_pre_old + current_dram_pre_large;
//      long pagesNvmPre =  current_nvm_pre_old + current_nvm_pre_large;
//
//      Log.write("------------------------ Collection ---------------------");
//      Log.writeln("[Pre-collection ");
//      Log.write("DRAM = ");
//      Log.writeln(pagesDramPre);
//      Log.write("NVM = ");
//      Log.write(pagesNvmPre);
//      Log.writeln("--------------------------------------------------------");
//    }
//    super.printPreStats();
//  }
//
//  @Override
//  public void printPostStats() {
//    if(gcFullHeap && Stats.gatheringStats()) {
//      long current_dram_post_old   = immixDramSpace.reservedPages();
//      long current_nvm_post_old    = immixNvmSpace.reservedPages();
//      long current_dram_post_large = Plan.loDramSpace.reservedPages();
//      long current_nvm_post_large = Plan.loNvmSpace.reservedPages();
//
//      long pagesDramPost = current_dram_post_old + current_dram_post_large;
//      long pagesNvmPost =  current_nvm_post_old + current_nvm_post_large;
//
//      Log.write("------------------------ Collection ---------------------");
//      Log.write("[Post-collection ");
//      Log.write("DRAM = ");
//      Log.write(pagesDramPost);
//      Log.write("NVM = ");
//      Log.write(pagesNvmPost);
//      Log.writeln("--------------------------------------------------------");
//    }
//    super.printPostStats();
//  }


}
