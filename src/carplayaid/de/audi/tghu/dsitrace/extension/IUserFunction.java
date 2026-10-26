package de.audi.tghu.dsitrace.extension;

/**
 * Same signature as the DSITracer interface. ua.mib.rgi.RgiModule implements it, and the stock
 * lsd.sh starts the HMI without DSITracer (DSITRACER_ACTIVATED=no), so without this copy RgiModule
 * fails to load and route guidance never autostarts.
 */
public interface IUserFunction {
    void execute(String arguments) throws Exception;
}
