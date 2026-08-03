package com.yumelium.yumelium.client.bench;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /ylbench} — the multi-Sludge-Menace render benchmark (task #15). Registered only in singleplayer
 * (YumeliumMod.serverStarting, physical-client gate), so {@code execute} runs on the INTEGRATED SERVER thread —
 * spawning and killing entities needs no marshalling; only {@code measure} hops to the client thread.
 *
 * <p>Everything Betweenlands is reached WITHOUT compile-time references (classloader invariant): the boss spawns
 * via its registry name {@code thebetweenlands:sludge_menace}, and anchoring uses reflection on
 * {@code setPositionToAnchor(BlockPos, EnumFacing, EnumFacing)} — Betweenlands' OWN method (never SRG-remapped, so
 * the name is stable dev and prod). That call replicates BL's boss-room spawn exactly
 * (TileEntityDecayPitControl: {@code setPositionToAnchor(pos, UP, NORTH)}); without it the boss falls and runs a
 * nondeterministic 50-probe self-anchor search every 3rd tick.</p>
 *
 * <p>Benchmark protocol (from the 2026-08-04 anatomy research): non-peaceful difficulty (peaceful setDeads the
 * boss instantly), CREATIVE observer (not a valid AI target → the boss idles, no bulge adds — reproducible),
 * vsync/frame cap off. Each boss brings 17 tracked DummyPart server entities. {@code clear} uses setDead, which
 * deliberately skips the death path that would mark a real dungeon defeated.</p>
 */
public class YlBenchCommand extends CommandBase {

    private static final ResourceLocation SLUDGE_MENACE = new ResourceLocation("thebetweenlands", "sludge_menace");
    private static final String MENACE_CLASS_PREFIX = "thebetweenlands.common.entity.mobs.EntitySludgeMenace";
    private static final String BENCH_TAG = "ylbench";

    @Override
    public String getName() {
        return "ylbench";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ylbench spawn <n> [radius] | measure [sec] | clear | status";
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true; // dev harness; SP-only registration
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "spawn", "measure", "clear", "status");
        }
        return java.util.Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new CommandException(getUsage(sender));
        }
        switch (args[0]) {
            case "spawn":
                spawn(sender, args);
                break;
            case "clear":
                clear(sender);
                break;
            case "measure": {
                final int sec = args.length > 1 ? clamp(parseInt(args[1]), 3, 120) : 10;
                // BenchSession samples on the client render loop — start it there.
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> BenchSession.INSTANCE.start(sec));
                reply(sender, TextFormatting.GRAY, "measuring " + sec + "s ... (summary arrives in chat)");
                break;
            }
            case "status": {
                int[] c = countBenchEntities(sender.getEntityWorld());
                reply(sender, TextFormatting.GRAY, "menace=" + c[0] + " dummies=" + c[1]
                        + " measuring=" + BenchSession.INSTANCE.isActive());
                break;
            }
            default:
                throw new CommandException(getUsage(sender));
        }
    }

    private void spawn(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new CommandException("/ylbench spawn <n> [radius]");
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        int n = clamp(parseInt(args[1]), 1, 32);
        int radius = args.length > 2 ? clamp(parseInt(args[2]), 4, 32) : 12;
        if (world.getDifficulty() == EnumDifficulty.PEACEFUL) {
            reply(sender, TextFormatting.YELLOW, "WARNING: peaceful difficulty setDeads the boss instantly — switch difficulty first");
        }
        int spawned = 0;
        for (int i = 0; i < n; i++) {
            Entity ent = net.minecraft.entity.EntityList.createEntityByIDFromName(SLUDGE_MENACE, world);
            if (ent == null) {
                reply(sender, TextFormatting.RED, "Betweenlands is not installed (unknown entity " + SLUDGE_MENACE + ")");
                return;
            }
            double angle = (Math.PI * 2.0 * i) / n;
            double x = player.posX + radius * Math.cos(angle);
            double z = player.posZ + radius * Math.sin(angle);
            int y = floorSnap(world, x, (int) Math.floor(player.posY), z);
            float yaw = (float) (Math.toDegrees(angle) + 270.0) % 360.0F; // face the player
            ent.setLocationAndAngles(x, y, z, yaw, 0.0F);
            // BL's own boss-room recipe: anchor to the floor block below, UP-facing. Reflection because the method
            // lives on EntityWallFace (BL's own name — stable dev/prod). Failure is non-fatal: the boss self-anchors
            // within seconds via its 50-probe search, just less deterministically.
            try {
                ent.getClass().getMethod("setPositionToAnchor", BlockPos.class, EnumFacing.class, EnumFacing.class)
                        .invoke(ent, new BlockPos((int) Math.floor(x), y - 1, (int) Math.floor(z)), EnumFacing.UP, EnumFacing.NORTH);
            } catch (Throwable t) {
                reply(sender, TextFormatting.YELLOW, "setPositionToAnchor failed (" + t.getClass().getSimpleName()
                        + ") — boss will self-anchor");
            }
            if (ent instanceof EntityLiving) {
                ((EntityLiving) ent).enablePersistence();
            }
            ent.addTag(BENCH_TAG);
            if (world.spawnEntity(ent)) {
                spawned++;
            }
        }
        reply(sender, TextFormatting.GREEN, "spawned " + spawned + " sludge menace (ring r=" + radius
                + "); each brings 17 dummy parts. Creative observer recommended.");
    }

    private void clear(ICommandSender sender) {
        World world = sender.getEntityWorld();
        int killed = 0;
        for (Entity e : new ArrayList<>(world.loadedEntityList)) {
            // Tagged bosses + belt-and-braces class match (catches the boss AND EntitySludgeMenace$DummyPart without
            // loading BL classes — getName() on an already-loaded instance is safe). Dummies also self-clean once
            // their parent is dead (updatePositioning parent-dead check).
            if (e.getTags().contains(BENCH_TAG) || e.getClass().getName().startsWith(MENACE_CLASS_PREFIX)) {
                e.setDead();
                killed++;
            }
        }
        reply(sender, TextFormatting.GREEN, "cleared " + killed + " bench entities");
    }

    /** Walks down from startY until the block below (x,z) is solid ground for the anchor — max 12 steps. */
    private static int floorSnap(World world, double x, int startY, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int y = startY;
        for (int i = 0; i < 12 && world.isAirBlock(new BlockPos(bx, y - 1, bz)); i++) {
            y--;
        }
        return y;
    }

    /** @return {menace count, dummy count} by class-name match (no BL class loading). */
    static int[] countBenchEntities(World world) {
        int menace = 0;
        int dummies = 0;
        for (Entity e : world.loadedEntityList) {
            String name = e.getClass().getName();
            if (name.equals(MENACE_CLASS_PREFIX)) {
                menace++;
            } else if (name.startsWith(MENACE_CLASS_PREFIX + "$")) {
                dummies++;
            }
        }
        return new int[]{menace, dummies};
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void reply(ICommandSender sender, TextFormatting color, String msg) {
        TextComponentString c = new TextComponentString("[ylbench] " + msg);
        c.getStyle().setColor(color);
        sender.sendMessage(c);
    }
}
