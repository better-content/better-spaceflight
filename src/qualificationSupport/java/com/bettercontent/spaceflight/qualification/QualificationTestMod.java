package com.bettercontent.spaceflight.qualification;

import net.minecraftforge.fml.common.Mod;

/**
 * Nonshipping, inert test namespace. Intentionally does NOT register GameTests,
 * listeners, commands, travel providers, or a runtime hook. Merely compiling
 * qualificationSupport never modifies the historical probe or main mod.
 */
@Mod(QualificationTestMod.ID)
public final class QualificationTestMod {
    public static final String ID = "better_spaceflight_qualification";
    public QualificationTestMod() {} // no registration until a separately reviewed real production backend exists
}
