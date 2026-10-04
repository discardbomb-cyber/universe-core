package dev.heiko.universe;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.heiko.universe.core.LazyUniverseCatalog;
import dev.heiko.universe.core.Realm;
import dev.heiko.universe.core.SectorKey;
import dev.heiko.universe.core.Versions;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator-only development travel; not the production ship flight protocol. */
public final class UniverseCommands {
    private UniverseCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("universe").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("status").executes(ctx -> {
            var source = ctx.getSource();
            var manifest = UniverseManifest.get(source.getServer());
            source.sendSuccess(() -> Component.literal("Universe alpha | world=" + manifest.worldId()
                + " | realms: Milky Way, Silent Crown (planned), Crimson Forge (planned)"), false);
            return Command.SINGLE_SUCCESS;
        }));
        var visit = Commands.literal("visit");
        for (String name : new String[]{"space", "shipyards", "barren", "temperate"}) {
            visit.then(Commands.literal(name).executes(ctx -> visit(ctx.getSource(), name)));
        }
        root.then(visit);
        root.then(Commands.literal("sector")
            .then(Commands.argument("realm", StringArgumentType.word())
                .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    new String[]{"milky_way", "end", "nether"}, builder))
                .then(Commands.argument("x", IntegerArgumentType.integer(-128, 127))
                    .then(Commands.argument("y", IntegerArgumentType.integer(-4, 3))
                        .then(Commands.argument("z", IntegerArgumentType.integer(-128, 127))
                            .executes(ctx -> {
                                var source = ctx.getSource();
                                String id = StringArgumentType.getString(ctx, "realm");
                                Realm realm = null;
                                for (Realm candidate : Realm.values()) if (candidate.id().equals(id)) realm = candidate;
                                if (realm == null) {
                                    source.sendFailure(Component.literal("Unknown realm: " + id));
                                    return 0;
                                }
                                var manifest = UniverseManifest.get(source.getServer());
                                var catalog = new LazyUniverseCatalog(manifest.seed(), Versions.CURRENT, 8);
                                var key = new SectorKey(realm, IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"));
                                var sector = catalog.sector(key);
                                int bodies = sector.systems().stream().mapToInt(system -> system.bodies().size()).sum();
                                source.sendSuccess(() -> Component.literal(key + " | systems=" + sector.systems().size()
                                    + " | bodies=" + bodies + " | metadata only, no chunks loaded"), false);
                                return Command.SINGLE_SUCCESS;
                            }))))));
        root.then(Commands.literal("home").executes(ctx -> {
            var player = ctx.getSource().getPlayerOrException();
            var level = ctx.getSource().getServer().overworld();
            var spawn = level.getSharedSpawnPos();
            level.getChunk(spawn);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
            var feet = new BlockPos(spawn.getX(), y, spawn.getZ());
            if (!safeArrival(level, feet)) {
                ctx.getSource().sendFailure(Component.literal("Home arrival is unsafe; no blocks changed."));
                return 0;
            }
            player.teleportTo(level, spawn.getX() + 0.5, y, spawn.getZ() + 0.5,
                player.getYRot(), player.getXRot());
            return Command.SINGLE_SUCCESS;
        }));
        event.getDispatcher().register(root);
    }

    private static int visit(CommandSourceStack source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(UniverseMod.ID, name));
        ServerLevel target = source.getServer().getLevel(key);
        if (target == null) {
            source.sendFailure(Component.literal("Dimension unavailable: " + key.location()));
            return 0;
        }
        UniverseManifest.get(source.getServer());
        target.getChunk(0, 0);
        int y;
        if (name.equals("space") || name.equals("shipyards")) {
            y = 81;
            // Never replace saved player blocks. A small arrival pad is a development aid.
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                var pos = new BlockPos(x, y - 1, z);
                if (target.isEmptyBlock(pos)) target.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            }
        } else {
            y = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        }
        var feet = new BlockPos(0, y, 0);
        if (!safeArrival(target, feet)) {
            source.sendFailure(Component.literal("Arrival occupied or unsafe; existing blocks preserved."));
            return 0;
        }
        player.teleportTo(target, 0.5, y, 0.5, player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("Universe development destination: " + name), false);
        return Command.SINGLE_SUCCESS;
    }

    private static boolean safeArrival(ServerLevel level, BlockPos feet) {
        var support = level.getBlockState(feet.below());
        return level.getWorldBorder().isWithinBounds(feet)
            && feet.getY() > level.getMinBuildHeight() && feet.getY() + 1 < level.getMaxBuildHeight()
            && level.isEmptyBlock(feet) && level.isEmptyBlock(feet.above())
            && support.getFluidState().isEmpty()
            && !support.getCollisionShape(level, feet.below()).isEmpty()
            && !support.is(Blocks.MAGMA_BLOCK) && !support.is(Blocks.CACTUS);
    }
}
