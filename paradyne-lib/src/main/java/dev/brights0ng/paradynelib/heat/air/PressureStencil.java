// Written by ChatGPT; extended by ChatGPT with order-preserving triangular row specialization.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;

/** Cached row stencil and incomplete Cholesky factor of the reference-eliminated operator.
 * All graph work happens at construction; projection and triangular solves reuse primitive buffers.
 */
final class PressureStencil {
    private final double[] diagonal, values, factor, inverseDiagonal;
    private final int[] start, column, split;
    private final boolean incomplete;
    private final boolean unroll;

    PressureStencil(FlowGeometry g,double[] weights,double[] diagonal,boolean[] pinned,
                    PressureProjector.Preconditioner method) {
        this(g,weights,diagonal,pinned,method,false);
    }

    PressureStencil(FlowGeometry g,double[] weights,double[] diagonal,boolean[] pinned,
                    PressureProjector.Preconditioner method,boolean unroll) {
        this.unroll=unroll;
        this.diagonal=diagonal;int n=diagonal.length;
        start=new int[n+1];split=new int[n];
        for(int f=0;f<weights.length;f++) {
            int l=g.low[f],h=g.high[f];
            if(weights[f]>0 && l>=0 && h>=0 && !pinned[l] && !pinned[h]) { start[l+1]++;start[h+1]++; }
        }
        for(int i=0;i<n;i++) { start[i+1]+=start[i]; }
        column=new int[start[n]];values=new double[start[n]];int[] cursor=start.clone();
        for(int f=0;f<weights.length;f++) {
            int l=g.low[f],h=g.high[f];
            if(weights[f]>0 && l>=0 && h>=0 && !pinned[l] && !pinned[h]) {
                int a=cursor[l]++,b=cursor[h]++;column[a]=h;column[b]=l;values[a]=values[b]=-weights[f];
            }
        }
        // Typical row degree is six; insertion sort also handles partial-shape component pairs.
        for(int i=0;i<n;i++) {
            for(int k=start[i]+1;k<start[i+1];k++) {
                int c=column[k],j=k;double v=values[k];
                while(j>start[i] && column[j-1]>c) { column[j]=column[j-1];values[j]=values[j-1];j--; }
                column[j]=c;values[j]=v;
            }
        }
        // Consolidate any repeated neighbor rather than assuming one geometric patch per matrix entry.
        int write=0,oldStart=0;
        for(int i=0;i<n;i++) {
            int oldEnd=start[i+1];start[i]=write;
            for(int k=oldStart;k<oldEnd;k++) {
                if(write>start[i] && column[write-1]==column[k]) { values[write-1]+=values[k]; }
                else { column[write]=column[k];values[write++]=values[k]; }
            }
            oldStart=oldEnd;int middle=start[i];while(middle<write && column[middle]<i) { middle++; }split[i]=middle;
        }
        start[n]=write;
        factor=method!=PressureProjector.Preconditioner.DIAGONAL?values.clone():null;
        inverseDiagonal=new double[n];
        incomplete=factor!=null && factorize(method==PressureProjector.Preconditioner.MODIFIED_INCOMPLETE_CHOLESKY);
        if(!incomplete) {
            for(int i=0;i<n;i++) { inverseDiagonal[i]=diagonal[i]>0?1/diagonal[i]:0; }
        }
    }

    boolean incomplete() { return incomplete; }

    private boolean factorize(boolean modified) {
        // Right-looking elimination lets dropped fill contribute to both affected diagonals. At tau=0
        // this is ordinary IC(0); tau=.97 approximately preserves row sums and improves long-wave errors.
        System.arraycopy(diagonal,0,inverseDiagonal,0,diagonal.length);double tau=modified?.97:0;
        for(int k=0;k<diagonal.length;k++) {
            double pivot=inverseDiagonal[k];
            if(modified && pivot<.25*diagonal[k]) { pivot=diagonal[k]; }
            if(!Double.isFinite(pivot) || pivot<=diagonal[k]*1e-12 || !(pivot>0)) { return false; }
            inverseDiagonal[k]=1/Math.sqrt(pivot);
            for(int e=split[k];e<start[k+1];e++) {
                int i=column[e];double lik=factor[e]*inverseDiagonal[k];factor[e]=lik;
                int transpose=Arrays.binarySearch(column,start[i],split[i],k);
                if(transpose<0) { throw new IllegalStateException("Pressure stencil must be symmetric"); }
                factor[transpose]=lik;inverseDiagonal[i]-=lik*lik;
            }
            for(int e=split[k];e<start[k+1];e++) for(int f=e+1;f<start[k+1];f++) {
                int i=column[e],j=column[f];double fill=factor[e]*factor[f];
                int entry=Arrays.binarySearch(column,split[i],start[i+1],j);
                if(entry>=0) { factor[entry]-=fill; }
                else { inverseDiagonal[i]-=tau*fill;inverseDiagonal[j]-=tau*fill; }
            }
        }
        return true;
    }

    void apply(double[] input,double[] output) {
        for(int i=0;i<diagonal.length;i++) {
            double sum=diagonal[i]*input[i];
            for(int e=start[i];e<start[i+1];e++) { sum+=values[e]*input[column[e]]; }
            output[i]=sum;
        }
    }

    double applyAndDot(double[] input,double[] output) {
        double dot=0;
        for(int i=0;i<diagonal.length;i++) {
            double sum=diagonal[i]*input[i];
            for(int e=start[i];e<start[i+1];e++) { sum+=values[e]*input[column[e]]; }
            output[i]=sum;dot+=sum*input[i];
        }
        return dot;
    }

    double precondition(double[] input,double[] output) {
        double dot=0;
        if(!incomplete) {
            for(int i=0;i<diagonal.length;i++) { output[i]=input[i]*inverseDiagonal[i];dot+=input[i]*output[i]; }return dot;
        }
        if(unroll) { return preconditionUnrolled(input,output); }
        for(int i=0;i<diagonal.length;i++) {
            double sum=input[i];for(int e=start[i];e<split[i];e++) { sum-=factor[e]*output[column[e]]; }
            output[i]=sum*inverseDiagonal[i];
        }
        for(int i=diagonal.length-1;i>=0;i--) {
            double sum=output[i];for(int e=split[i];e<start[i+1];e++) { sum-=factor[e]*output[column[e]]; }
            output[i]=sum*inverseDiagonal[i];dot+=input[i]*output[i];
        }
        return dot;
    }

    private double preconditionUnrolled(double[] input,double[] output) {
        // Keep the reference subtraction order; arbitrary-degree partial-geometry rows retain the loop.
        for(int i=0;i<diagonal.length;i++) {
            double sum=input[i];int p=start[i],end=split[i];
            if(end-p==3) {
                sum-=factor[p]*output[column[p]];
                sum-=factor[p+1]*output[column[p+1]];
                sum-=factor[p+2]*output[column[p+2]];
            } else for(int e=p;e<end;e++) { sum-=factor[e]*output[column[e]]; }
            output[i]=sum*inverseDiagonal[i];
        }
        double dot=0;
        for(int i=diagonal.length-1;i>=0;i--) {
            double sum=output[i];int p=split[i],end=start[i+1];
            if(end-p==3) {
                sum-=factor[p]*output[column[p]];
                sum-=factor[p+1]*output[column[p+1]];
                sum-=factor[p+2]*output[column[p+2]];
            } else for(int e=p;e<end;e++) { sum-=factor[e]*output[column[e]]; }
            output[i]=sum*inverseDiagonal[i];dot+=input[i]*output[i];
        }
        return dot;
    }
}
