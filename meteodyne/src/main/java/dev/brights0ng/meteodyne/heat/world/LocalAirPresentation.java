// Written by ChatGPT.
// Updated by ChatGPT: carried owner-thread preparation explanations alongside unavailable readings.
package dev.brights0ng.meteodyne.heat.world;

/** One owner-thread guarded selection for a point reading and its matching immutable visual field. */
record LocalAirPresentation(long domainId,boolean retainHistory,LocalAirReading reading,LocalAirRun.Frame frame,String detail) {
    LocalAirPresentation(long domainId,boolean retainHistory,LocalAirReading reading,LocalAirRun.Frame frame) { this(domainId,retainHistory,reading,frame,""); }
    LocalAirPresentation detail(String message) { return new LocalAirPresentation(domainId,retainHistory,reading,frame,message); }
    static LocalAirPresentation missing(LocalAirReading.Status status,LocalAirReading.Reason reason) {
        return new LocalAirPresentation(0,false,LocalAirReading.missing(status,reason),null);
    }
}
