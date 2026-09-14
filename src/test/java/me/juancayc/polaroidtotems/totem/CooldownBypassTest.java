package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.domain.TotemDefinition;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-level bypass permission, checked without a server.
 *
 * <p>{@link CooldownService#isBypassing} takes a {@link Permissible} rather than a {@code Player}
 * for exactly this reason: the decision is "does this holder have one of two nodes", which needs a
 * permission oracle and nothing else. Stubbing {@code Permissible} with a name set leaves the
 * interesting half testable offline, the same split {@link
 * me.juancayc.polaroidtotems.skill.SkillValidator} uses for its Mythic oracle.
 *
 * <p>The service's scheduling and storage halves are deliberately untested here: they need a live
 * {@code Server} for {@code getAsyncScheduler()}. Their logic is the cache's, which
 * {@link CooldownCacheTest} covers in full.
 */
class CooldownBypassTest {

    /**
     * A holder of a fixed set of permission nodes.
     *
     * <p>Hand-written rather than mocked: the project pulls in no mocking framework, and only one of
     * these dozen methods carries any behaviour. The rest throw so a future call that quietly starts
     * depending on them fails loudly instead of reading a silent default.
     */
    private record StubPermissible(Set<String> granted) implements Permissible {

        @Override
        public boolean hasPermission(String name) {
            return granted.contains(name);
        }

        @Override
        public boolean hasPermission(Permission permission) {
            return hasPermission(permission.getName());
        }

        @Override
        public boolean isPermissionSet(String name) {
            return granted.contains(name);
        }

        @Override
        public boolean isPermissionSet(Permission permission) {
            return isPermissionSet(permission.getName());
        }

        @Override
        public boolean isOp() {
            return false;
        }

        @Override
        public void setOp(boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAttachment(PermissionAttachment attachment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recalculatePermissions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<PermissionAttachmentInfo> getEffectivePermissions() {
            throw new UnsupportedOperationException();
        }
    }

    private static Permissible holding(String... nodes) {
        return new StubPermissible(Set.of(nodes));
    }

    private static TotemDefinition totem(String id, long cooldownSeconds) {
        return new TotemDefinition(id, null, List.of(), "vanilla:TOTEM_OF_UNDYING", 1,
                cooldownSeconds, null, null, List.of(), List.of(), true, false, null);
    }

    /** Built with nulls: none of these cases reaches the cache, the repository or a scheduler. */
    private static CooldownService service() {
        return new CooldownService(null, new CooldownCache(), null);
    }

    @Test
    @DisplayName("the per-type node is a CHILD of the blanket one, so a wildcard grants every type")
    void perTypeNodeIsAChildOfTheBlanketOne() {
        // The shape is the feature: `polaroidtotems.cooldown.bypass.*` in a permission plugin only
        // covers every type because the per-type nodes sit underneath the blanket one.
        assertEquals("polaroidtotems.cooldown.bypass", CooldownService.BYPASS_ALL);
        assertEquals("polaroidtotems.cooldown.bypass.ember",
                CooldownService.bypassPermission("ember"));
        assertTrue(CooldownService.bypassPermission("ember").startsWith(CooldownService.BYPASS_ALL + "."));
    }

    @Test
    @DisplayName("a totem id is lowercased into the node, matching how totems.yml reads its keys")
    void perTypeNodeIsNormalized() {
        assertEquals("polaroidtotems.cooldown.bypass.ember",
                CooldownService.bypassPermission("  Ember "));
    }

    @Test
    @DisplayName("the blanket node exempts a player from every type")
    void blanketNodeCoversEveryType() {
        CooldownService service = service();
        Permissible player = holding(CooldownService.BYPASS_ALL);

        assertTrue(service.isBypassing(player, totem("ember", 300L)));
        assertTrue(service.isBypassing(player, totem("guardian", 3600L)));
    }

    @Test
    @DisplayName("the per-type node exempts a player from that type ALONE")
    void perTypeNodeCoversOnlyItsType() {
        CooldownService service = service();
        Permissible player = holding(CooldownService.bypassPermission("ember"));

        assertTrue(service.isBypassing(player, totem("ember", 300L)));
        assertFalse(service.isBypassing(player, totem("guardian", 3600L)),
                "an exemption for one type must not leak into another");
    }

    @Test
    @DisplayName("a player holding neither node is subject to every cooldown")
    void noNodeMeansNoBypass() {
        CooldownService service = service();
        Permissible player = holding();

        assertFalse(service.isBypassing(player, totem("ember", 300L)));
    }

    @Test
    @DisplayName("an unrelated permission does not accidentally match")
    void unrelatedNodesDoNotMatch() {
        CooldownService service = service();

        assertFalse(service.isBypassing(holding("polaroidtotems.admin"), totem("ember", 300L)));
        assertFalse(service.isBypassing(holding("polaroidtotems.cooldown"), totem("ember", 300L)),
                "a prefix of the node is not the node");
        assertFalse(service.isBypassing(holding("polaroidtotems.cooldown.bypass.embers"),
                totem("ember", 300L)), "a longer id starting with this one is a different type");
    }
}
