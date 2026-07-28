package me.jellysquid.mods.sodium.client;

/**
 * EMPTY MARKER CLASS — the canonical "a Sodium port is installed" detection name on 1.12.2.
 *
 * <p>Vintagium (Asek3/sodium-1.12) registers this class as its launchwrapper tweaker, and because it was the only
 * 1.12.2 Sodium port for years, other mods detect "any Sodium port" by {@code Class.forName} on this exact name and
 * then auto-disable their Sodium-incompatible features:</p>
 *
 * <ul>
 * <li><b>FermiumASM</b> ({@code NormalTransformer.<clinit>}): forces {@code squashBakedQuads} OFF — "incompatible
 *     with Sodium". Without this marker it stayed ON in production and made {@code BakedQuad.getSprite()} abstract,
 *     crashing our mesher with an AbstractMethodError on the first chunk build (2026-07-28, PrismLauncher instance).
 *     Timing verified from that launch's log: our coremod tweak is called BEFORE the FermiumASM transformer
 *     initializes, so the probe sees this class.</li>
 * <li><b>VintageFix</b> ({@code VintageFixCore.VINTAGIUM}): disables its {@code mixin.chunk_rendering},
 *     {@code mixin.bugfix.entity_disappearing} and {@code mixin.invisible_subchunks} packages — all coupled to the
 *     vanilla chunk renderer we replace. (Its plugin classloads during coremod discovery, likely before our jar's
 *     URL is added, so this arm may not fire — ThirdPartyConfigEnforcer covers entity_disappearing regardless.)</li>
 * </ul>
 *
 * <p>Yumelium IS a Sodium port, so declaring ourselves via the established name is the honest fix — it activates the
 * guards those mods already ship instead of us chasing each incompatibility by config. Never instantiated, never
 * registered as a tweaker (that requires a manifest {@code TweakClass} attribute we don't set); the probes use
 * {@code Class.forName}, which initializes the class, so it must stay free of static state.</p>
 */
public final class SodiumMixinTweaker {
    private SodiumMixinTweaker() {
    }
}
