package dev.mineedge.integration;

import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaczSprintCompatibilityTest {
    /** The optional public API contract from TaCZ 1.20.1; no gun mod is required by these tests. */
    public interface GunOperator {
        boolean getProcessedSprintStatus(boolean requested);
    }

    @Test void gunPermissionVetoesAndResumesEvenWhenTheEntityFlagIsFalse() throws ReflectiveOperationException {
        var permission = TaczSprintCompatibility.bind(GunOperator.class);
        var player = mock(Player.class, withSettings().extraInterfaces(GunOperator.class));
        var operator = (GunOperator) player;
        assertFalse(player.isSprinting());
        when(operator.getProcessedSprintStatus(true)).thenReturn(false);
        assertFalse(permission.test(player), "Aiming/reload must veto a fresh sprint request");
        when(operator.getProcessedSprintStatus(true)).thenReturn(true);
        assertTrue(permission.test(player), "Gun permission must resume without needing an already-set sprint flag");
        verify(operator, times(2)).getProcessedSprintStatus(true);
        verify(operator, never()).getProcessedSprintStatus(false);
    }

    @Test void playersWithoutTheOptionalInterfaceKeepNativeSprintPermission() throws ReflectiveOperationException {
        assertTrue(TaczSprintCompatibility.bind(GunOperator.class).test(mock(Player.class)));
        assertTrue(TaczSprintCompatibility.allowed(mock(Player.class)), "The installed test mod list does not include TaCZ");
    }
}
