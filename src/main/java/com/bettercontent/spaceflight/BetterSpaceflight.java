package com.bettercontent.spaceflight;

import net.minecraftforge.fml.common.Mod;
import com.bettercontent.spaceflight.vs.Vs2411Adapter;

/** Feasibility-stage entry point. No survival gameplay is released by this skeleton. */
@Mod(BetterSpaceflight.ID)
public final class BetterSpaceflight {
    public static final String ID = "better_spaceflight";

    public BetterSpaceflight() {
        Vs2411Adapter.requireSupportedVersion();
    }
}
