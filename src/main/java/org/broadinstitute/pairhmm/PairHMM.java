/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.pairhmm;

import org.broadinstitute.utils.MathUtils;

/**
 * Util class for performing the pair HMM for local alignment. Figure 4.3 in Durbin 1998 book.
 *
 * User: rpoplin
 * Date: 10/16/12
 */
public abstract class PairHMM {
    protected static final Byte MAX_CACHED_QUAL = Byte.MAX_VALUE;
    protected static final byte DEFAULT_GOP = (byte) 45;
    protected static final byte DEFAULT_GCP = (byte) 10;

    public enum HMM_IMPLEMENTATION {
        /* Very slow implementation which uses very accurate log10 sum functions. Only meant to be used as a reference test implementation */
        EXACT,
        /* PairHMM as implemented for the UnifiedGenotyper. Uses log10 sum functions accurate to only 1E-4 */
        ORIGINAL,
        /* Optimized version of the PairHMM which caches per-read computations and operations in real space to avoid costly sums of log10'ed likelihoods */
        LOGLESS_CACHING
    }

    protected double[][] matchMatrix = null;
    protected double[][] insertionMatrix = null;
    protected double[][] deletionMatrix = null;
    protected int maxHaplotypeLength, maxReadLength;
    protected int paddedMaxReadLength, paddedMaxHaplotypeLength;
    private boolean initialized = false;

    /**
     * Initialize this PairHMM, making it suitable to run against a read and haplotype with given lengths
     * @param haplotypeMaxLength the max length of haplotypes we want to use with this PairHMM
     * @param readMaxLength the max length of reads we want to use with this PairHMM
     */
    public void initialize( final int haplotypeMaxLength, final int readMaxLength ) {
        if ( readMaxLength <= 0 ) throw new IllegalArgumentException("READ_MAX_LENGTH must be > 0 but got " + readMaxLength);
        if ( haplotypeMaxLength <= 0 ) throw new IllegalArgumentException("HAPLOTYPE_MAX_LENGTH must be > 0 but got " + haplotypeMaxLength);

        maxHaplotypeLength = haplotypeMaxLength;
        maxReadLength = readMaxLength;

        // M, X, and Y arrays are of size read and haplotype + 1 because of an extra column for initial conditions and + 1 to consider the final base in a non-global alignment
        paddedMaxReadLength = readMaxLength + 2;
        paddedMaxHaplotypeLength = haplotypeMaxLength + 2;

        matchMatrix = new double[paddedMaxReadLength][paddedMaxHaplotypeLength];
        insertionMatrix = new double[paddedMaxReadLength][paddedMaxHaplotypeLength];
        deletionMatrix = new double[paddedMaxReadLength][paddedMaxHaplotypeLength];
        initialized = true;
    }

    /**
     * Compute the total probability of read arising from haplotypeBases given base substitution, insertion, and deletion
     * probabilities.
     *
     * Note on using hapStartIndex.  This allows you to compute the exact true likelihood of a full haplotypes
     * given a read, assuming that the previous calculation read over a full haplotype, recaching the read values,
     * starting only at the place where the new haplotype bases and the previous haplotype bases different.  This
     * index is 0-based, and can be computed with findFirstPositionWhereHaplotypesDiffer given the two haplotypes.
     * Note that this assumes that the read and all associated quals values are the same.
     *
     * @param haplotypeBases the full sequence (in standard SAM encoding) of the haplotype, must be >= than read bases in length
     * @param readBases the bases (in standard encoding) of the read, must be <= haplotype bases in length
     * @param readQuals the phred-scaled per base substitition quality scores of read.  Must be the same length as readBases
     * @param insertionGOP the phred-scaled per base insertion quality scores of read.  Must be the same length as readBases
     * @param deletionGOP the phred-scaled per base deletion quality scores of read.  Must be the same length as readBases
     * @param overallGCP the phred-scaled gap continuation penalties scores of read.  Must be the same length as readBases
     * @param hapStartIndex start the hmm calculation at this offset in haplotype bases.  Used in the caching calculation
     *                      where multiple haplotypes are used, and they only diff starting at hapStartIndex
     * @param recacheReadValues if false, we don't recalculate any cached results, assuming that readBases and its associated
     *                          parameters are the same, and only the haplotype bases are changing underneath us
     * @return the log10 probability of read coming from the haplotype under the provided error model
     */
    public final double computeReadLikelihoodGivenHaplotypeLog10( final byte[] haplotypeBases,
                                                                  final byte[] readBases,
                                                                  final byte[] readQuals,
                                                                  final byte[] insertionGOP,
                                                                  final byte[] deletionGOP,
                                                                  final byte[] overallGCP,
                                                                  final int hapStartIndex,
                                                                  final boolean recacheReadValues ) {
        if ( ! initialized ) throw new IllegalStateException("Must call initialize before calling computeReadLikelihoodGivenHaplotypeLog10");
        if ( haplotypeBases == null ) throw new IllegalArgumentException("haplotypeBases cannot be null");
        if ( haplotypeBases.length > maxHaplotypeLength ) throw new IllegalArgumentException("Haplotype bases is too long, got " + haplotypeBases.length + " but max is " + maxHaplotypeLength);
        if ( readBases == null ) throw new IllegalArgumentException("readBases cannot be null");
        if ( readBases.length > maxReadLength ) throw new IllegalArgumentException("readBases is too long, got " + readBases.length + " but max is " + maxReadLength);
        if ( readQuals.length != readBases.length ) throw new IllegalArgumentException("Read bases and read quals aren't the same size: " + readBases.length + " vs " + readQuals.length);
        if ( insertionGOP.length != readBases.length ) throw new IllegalArgumentException("Read bases and read insertion quals aren't the same size: " + readBases.length + " vs " + insertionGOP.length);
        if ( deletionGOP.length != readBases.length ) throw new IllegalArgumentException("Read bases and read deletion quals aren't the same size: " + readBases.length + " vs " + deletionGOP.length);
        if ( overallGCP.length != readBases.length ) throw new IllegalArgumentException("Read bases and overall GCP aren't the same size: " + readBases.length + " vs " + overallGCP.length);
        if ( hapStartIndex < 0 || hapStartIndex > haplotypeBases.length ) throw new IllegalArgumentException("hapStartIndex is bad, must be between 0 and haplotype length " + haplotypeBases.length + " but got " + hapStartIndex);

        double result = subComputeReadLikelihoodGivenHaplotypeLog10(haplotypeBases, readBases, readQuals, insertionGOP, deletionGOP, overallGCP, hapStartIndex, recacheReadValues);

        if (result > 0.0)
            throw new IllegalStateException("Log Probability cannot be greater than zero: " + result);

        // TODO -- remove max when PairHMM no longer returns likelihoods >= 0
        result = Math.min(result, 0.0);

        if ( MathUtils.goodLog10Probability(result) )
            return result;
        else
            throw new IllegalStateException("Bad likelihoods detected: " + result);
//            return result;
    }

    /**
     * To be overloaded by subclasses to actually do calculation for #computeReadLikelihoodGivenHaplotypeLog10
     */
    protected abstract double subComputeReadLikelihoodGivenHaplotypeLog10( final byte[] haplotypeBases,
                                                                           final byte[] readBases,
                                                                           final byte[] readQuals,
                                                                           final byte[] insertionGOP,
                                                                           final byte[] deletionGOP,
                                                                           final byte[] overallGCP,
                                                                           final int hapStartIndex,
                                                                           final boolean recacheReadValues );

    /**
     * Print out the core hmm matrices for debugging
     */
    protected void dumpMatrices() {
        dumpMatrix("matchMetricArray", matchMatrix);
        dumpMatrix("insertionMatrix", insertionMatrix);
        dumpMatrix("deletionMatrix", deletionMatrix);
    }

    /**
     * Print out in a human readable form the matrix for debugging
     * @param name the name of this matrix
     * @param matrix the matrix of values
     */
    private void dumpMatrix(final String name, final double[][] matrix) {
        System.out.printf("%s%n", name);
        for ( int i = 0; i < matrix.length; i++) {
            System.out.printf("\t%s[%d]", name, i);
            for ( int j = 0; j < matrix[i].length; j++ ) {
                if ( Double.isInfinite(matrix[i][j]) )
                    System.out.printf(" %15s", String.format("%f", matrix[i][j]));
                else
                    System.out.printf(" % 15.5e", matrix[i][j]);
            }
            System.out.println();
        }
    }
}
