package dev.mineedge.integration;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.BoxWorld;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfaceContact;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Side-effect-free tagged surface queries shared by live motion and replay. Never loads chunks. */
public final class MinecraftMovementSurfaces {
    private static final double CLIMB_ATTACH_RADIUS = 1;
    private record Shape(CollisionBox box, BlockPos pos, BlockState state) {}
    private final Player player;
    public MinecraftMovementSurfaces(Player player) { this.player = player; }

    public static boolean managedClimbable(Player player) {
        var state = player.level().getBlockState(player.blockPosition());
        if (!ladder(state) || wet(state)) return false;
        // Columns beyond the bounded custom route keep vanilla ladder ownership.
        var queries = new MinecraftMovementSurfaces(player);
        var route = queries.column(player.blockPosition(), MinecraftMotionAdapter.core(player.position()), null);
        return route != null && queries.valid(route);
    }

    public SurfaceContact support(MovementVector feet, MovementBody body) {
        double width = MovementPoses.dimensions(player, body).width();
        var probe = CollisionBox.body(feet.add(new MovementVector(0, 0.08, 0)), width, 0.02);
        var shapes = shapes(probe.swept(new MovementVector(0, -0.25, 0), 0.01));
        if (shapes == null) return null;
        var world = new BoxWorld(shapes.stream().map(Shape::box).toList());
        var hit = world.sweep(probe, new MovementVector(0, -0.25, 0));
        if (hit == null || hit.normal().y() < 0.7) return null;
        Shape selected = shapes.stream().filter(s -> s.box.equals(hit.shape())).findFirst().orElse(null);
        if (selected == null) return null;
        MovementVector downhill = MovementVector.ZERO, normal = hit.normal();
        if (selected.state.is(MovementTags.STEEP)) {
            if (selected.state.getBlock() instanceof StairBlock
                    && selected.state.getValue(BlockStateProperties.HALF) == Half.BOTTOM) {
                downhill = direction(selected.state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
                normal = downhill.scale(Math.sqrt(1 - 0.66 * 0.66)).add(new MovementVector(0, 0.66, 0));
            } else {
                double largest = 0;
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    MovementVector d = direction(side);
                    var at = new MovementVector(feet.x(), hit.point().y(), feet.z()).add(d.scale(0.75));
                    var lower = top(at, hit.point().y() + 0.05, hit.point().y() - 1.25);
                    if (lower == null || !lower.state.is(MovementTags.STEEP)) continue;
                    double fall = hit.point().y() - lower.box.max().y();
                    double ny = 0.75 / Math.sqrt(0.75 * 0.75 + fall * fall);
                    if (fall > largest && ny >= 0.6 - 1.0E-9 && ny < 0.7) {
                        largest = fall; downhill = d;
                        normal = d.scale(Math.sqrt(1 - ny * ny)).add(new MovementVector(0, ny, 0));
                    }
                }
            }
        }
        return new SurfaceContact(hit.shape(), core(selected.pos), normal, downhill,
                selected.state.is(MovementTags.SOFT), selected.state.is(MovementTags.STEP_UP));
    }

    public boolean step(CollisionBox shape) {
        var p = shape.min().add(shape.max()).scale(0.5);
        BlockPos pos = BlockPos.containing(p.x(), p.y(), p.z());
        return loaded(pos) && player.level().getBlockState(pos).is(MovementTags.STEP_UP);
    }
    public boolean stepSource(MovementVector source) {
        BlockPos pos = BlockPos.containing(source.x() + 1.0E-7, source.y() + 1.0E-7, source.z() + 1.0E-7);
        return loaded(pos) && player.level().getBlockState(pos).is(MovementTags.STEP_UP);
    }

    public ClimbRoute climb(MovementVector feet, MovementVector preferredNormal) {
        int minX = Mth.floor(feet.x() - CLIMB_ATTACH_RADIUS), maxX = Mth.floor(feet.x() + CLIMB_ATTACH_RADIUS);
        int minZ = Mth.floor(feet.z() - CLIMB_ATTACH_RADIUS), maxZ = Mth.floor(feet.z() + CLIMB_ATTACH_RADIUS);
        ClimbRoute best = null; double distance = Double.MAX_VALUE;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            for (int y = Mth.floor(feet.y() - 1); y <= Mth.floor(feet.y() + 1.8); y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!loaded(pos)) continue;
                var state = player.level().getBlockState(pos);
                if ((!ladder(state) && !pipe(state)) || wet(state)) continue;
                var route = column(pos, feet, preferredNormal);
                if (route == null || feet.y() < route.base().y() - 0.32 || feet.y() > route.top() + 0.5) continue;
                var delta = feet.subtract(route.base());
                var along = new MovementVector(route.normal().z(), 0, -route.normal().x());
                if (delta.horizontalSpeed() > CLIMB_ATTACH_RADIUS || Math.abs(delta.dot(along)) > 0.65) continue;
                double score = feet.subtract(route.feet(feet.y())).horizontalSpeed();
                if (score < distance) { distance = score; best = route; }
            }
        return best;
    }

    public boolean valid(ClimbRoute route) {
        BlockPos pos = BlockPos.containing(route.source().x(), route.source().y(), route.source().z());
        if (!loaded(pos)) return false;
        var canonical = column(pos, route.feet(route.base().y()), route.normal());
        if (canonical == null) return false;
        if (!route.pipe()) return route.equals(canonical);
        var offset = route.base().subtract(canonical.base());
        return canonical.pipe() && route.top() == canonical.top() && route.source().equals(canonical.source())
                && Math.abs(route.normal().horizontalSpeed() - 1) < 1.0E-9 && route.normal().y() == 0
                && offset.y() == 0 && Math.abs(offset.dot(route.normal())) < 1.0E-9
                && offset.horizontalSpeed() <= 0.5 + 1.0E-9;
    }

    /** Continuous side movement keeps the source column in replay state, including across block seams. */
    public ClimbRoute sideways(ClimbRoute route, double height, double distance) {
        if (!route.pipe() || !valid(route)) return null;
        BlockPos source = BlockPos.containing(route.source().x(), route.source().y(), route.source().z());
        boolean connected = false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (connected(source, height, route.top(), side)) connected = true;
            if (!loaded(source.relative(side).atY(Mth.floor(height)))) return null;
        }
        if (!connected) {
            var center = new MovementVector(source.getX() + 0.5, route.base().y(), source.getZ() + 0.5);
            return route.base().subtract(center).horizontalSpeed() < 1.0E-9 ? route.orbit(distance) : null;
        }
        // Connected panels slide along a face. A changed connection cannot turn a diagonal orbit into a panel slide.
        var n = route.normal();
        if (Math.abs(n.x()) > 1.0E-9 && Math.abs(n.z()) > 1.0E-9) return null;
        var along = new MovementVector(n.z(), 0, -n.x());
        var base = route.base().add(along.scale(distance));
        BlockPos next = BlockPos.containing(base.x(), source.getY(), base.z());
        if (!next.equals(source)) {
            Direction direction = Direction.getNearest(along.x() * distance, 0, along.z() * distance);
            if (!connected(source, height, route.top(), direction) || !loaded(next)) return null;
        }
        var column = column(next, new MovementVector(base.x(), height, base.z()), n);
        if (column == null || !column.pipe() || Math.abs(base.subtract(column.base()).dot(n)) > 1.0E-9) return null;
        return new ClimbRoute(new MovementVector(base.x(), column.base().y(), base.z()), column.top(), n, true, column.source());
    }

    private boolean connected(BlockPos source, double height, double top, Direction side) {
        for (int y = Math.max(source.getY(), Mth.floor(height)); y <= Math.min(Mth.floor(top - 1.0E-7), Mth.floor(height + MovementBody.STANDING.height())); y++) {
            var at = source.atY(y); var neighbor = at.relative(side);
            if (!loaded(at) || !loaded(neighbor)) continue;
            var state = player.level().getBlockState(at); var other = player.level().getBlockState(neighbor);
            if (!pipe(state) || !pipe(other) || wet(state) || wet(other)) continue;
            var shape = state.getCollisionShape(player.level(), at, CollisionContext.of(player));
            var adjacent = other.getCollisionShape(player.level(), neighbor, CollisionContext.of(player));
            if (shape.isEmpty() || adjacent.isEmpty()) continue;
            var a = shape.bounds(); var b = adjacent.bounds();
            boolean touches = switch (side) {
                case EAST -> a.maxX >= 1 && b.minX <= 0;
                case WEST -> a.minX <= 0 && b.maxX >= 1;
                case SOUTH -> a.maxZ >= 1 && b.minZ <= 0;
                case NORTH -> a.minZ <= 0 && b.maxZ >= 1;
                default -> false;
            };
            if (touches) return true;
        }
        return false;
    }

    private ClimbRoute column(BlockPos pos, MovementVector feet, MovementVector preferredNormal) {
        var state = player.level().getBlockState(pos);
        boolean pipe = pipe(state);
        if ((!pipe && !ladder(state)) || wet(state)) return null;
        int bottom = pos.getY(), top = bottom;
        for (int i = 0; i < 32; i++) {
            BlockPos below = pos.atY(bottom - 1);
            if (!loaded(below)) return null;
            if (!same(state, player.level().getBlockState(below), pipe)) break;
            if (i == 31) return null;
            bottom--;
        }
        for (int i = 0; i < 32; i++) {
            BlockPos above = pos.atY(top + 1);
            if (!loaded(above)) return null;
            if (!same(state, player.level().getBlockState(above), pipe)) break;
            if (i == 31) return null;
            top++;
        }
        BlockPos source = pos.atY(bottom);
        var shape = state.getCollisionShape(player.level(), source, CollisionContext.of(player));
        if (shape.isEmpty()) return null;
        var bounds = shape.bounds();
        MovementVector n;
        if (!pipe) n = direction(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        else if (preferredNormal != null && preferredNormal.horizontalSpeed() > 0.9) n = preferredNormal;
        else {
            double dx = feet.x() - pos.getX() - 0.5, dz = feet.z() - pos.getZ() - 0.5;
            n = Math.abs(dx) > Math.abs(dz) ? new MovementVector(Math.signum(dx), 0, 0) : new MovementVector(0, 0, dz >= 0 ? 1 : -1);
        }
        double x = pos.getX() + 0.5, z = pos.getZ() + 0.5;
        if (!pipe) {
            if (n.x() != 0) x = pos.getX() + (n.x() > 0 ? bounds.maxX : bounds.minX);
            if (n.z() != 0) z = pos.getZ() + (n.z() > 0 ? bounds.maxZ : bounds.minZ);
        }
        return new ClimbRoute(new MovementVector(x, bottom, z), top + bounds.maxY, n, pipe, core(source));
    }

    private boolean same(BlockState a, BlockState b, boolean pipe) {
        return !wet(b) && a.getBlock() == b.getBlock() && (pipe ? pipe(b)
                : ladder(b) && a.getValue(BlockStateProperties.HORIZONTAL_FACING) == b.getValue(BlockStateProperties.HORIZONTAL_FACING));
    }
    private static boolean ladder(BlockState state) { return state.is(MovementTags.LADDERS) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING); }
    private static boolean pipe(BlockState state) {
        return state.is(MovementTags.DRAINPIPES)
                && (!state.hasProperty(BlockStateProperties.AXIS) || state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y)
                && (!state.hasProperty(BlockStateProperties.FACING) || state.getValue(BlockStateProperties.FACING).getAxis() == Direction.Axis.Y);
    }
    private static boolean wet(BlockState state) { return !state.getFluidState().isEmpty(); }
    private boolean loaded(BlockPos pos) { return player.level().getChunkForCollisions(pos.getX() >> 4, pos.getZ() >> 4) != null; }
    private static MovementVector core(BlockPos pos) { return new MovementVector(pos.getX(), pos.getY(), pos.getZ()); }
    private static MovementVector direction(Direction direction) { return new MovementVector(direction.getStepX(), 0, direction.getStepZ()); }

    private Shape top(MovementVector at, double above, double below) {
        var probe = CollisionBox.body(new MovementVector(at.x(), above, at.z()), 0.02, 0.01);
        var shapes = shapes(probe.swept(new MovementVector(0, below - above, 0), 0.01));
        if (shapes == null) return null;
        var world = new BoxWorld(shapes.stream().map(Shape::box).toList());
        if (world.overlaps(probe)) return null;
        var hit = world.sweep(probe, new MovementVector(0, below - above, 0));
        return hit == null || hit.normal().y() < 0.7 ? null : shapes.stream().filter(s -> s.box.equals(hit.shape())).findFirst().orElse(null);
    }
    private ArrayList<Shape> shapes(CollisionBox region) {
        var area = MinecraftCollisionWorld.minecraft(region);
        if (!MinecraftCollisionWorld.loaded(player, area)) return null;
        var result = new ArrayList<Shape>();
        for (BlockPos mutable : BlockPos.betweenClosed(Mth.floor(area.minX) - 1, Mth.floor(area.minY) - 1, Mth.floor(area.minZ) - 1,
                Mth.floor(area.maxX) + 1, Mth.floor(area.maxY) + 1, Mth.floor(area.maxZ) + 1)) {
            BlockPos pos = mutable.immutable();
            var state = player.level().getBlockState(pos);
            var shape = state.getCollisionShape(player.level(), pos, CollisionContext.of(player));
            shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> result.add(new Shape(new CollisionBox(
                    new MovementVector(pos.getX() + x0, pos.getY() + y0, pos.getZ() + z0),
                    new MovementVector(pos.getX() + x1, pos.getY() + y1, pos.getZ() + z1)), pos, state)));
        }
        return result;
    }
}
