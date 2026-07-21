package ru.xrshop.server.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModPermissionsTest {
    @Test void requiredForgePermissionNodesHaveExactNames() {
        assertEquals("minecraftshop.open", ModPermissions.OPEN.getNodeName());
        assertEquals("minecraftshop.buy", ModPermissions.BUY.getNodeName());
        assertEquals("minecraftshop.balance", ModPermissions.BALANCE.getNodeName());
        assertEquals("minecraftshop.admin", ModPermissions.ADMIN.getNodeName());
        assertEquals("minecraftshop.admin.editor", ModPermissions.EDITOR.getNodeName());
        assertEquals("minecraftshop.admin.review", ModPermissions.REVIEW.getNodeName());
    }

    @Test void masterOrOperationPermissionGrantsAdminAccess() {
        assertTrue(ModPermissions.adminAccess(true, false));
        assertTrue(ModPermissions.adminAccess(false, true));
        assertFalse(ModPermissions.adminAccess(false, false));
    }
}
