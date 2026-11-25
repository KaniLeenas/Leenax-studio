package com.leenax.studio.projects;

import com.leenax.studio.auth.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAccessTest {

    @Test
    void rolesSayWhoEditsAndWhoReviews() {
        assertTrue(ProjectMembers.Role.OWNER.canEdit());
        assertTrue(ProjectMembers.Role.EDITOR.canEdit());
        assertFalse(ProjectMembers.Role.REVIEWER.canEdit());
        assertFalse(ProjectMembers.Role.VIEWER.canEdit());
        assertTrue(ProjectMembers.Role.REVIEWER.canReview());
        assertFalse(ProjectMembers.Role.EDITOR.canReview());
    }

    @Test
    void anAdministratorWhoIsNotAMemberShowsAsAdmin() {
        User admin = User.firstAdmin("k@leenax.lk", "Kani", "h");
        User member = User.invited("a@leenax.lk", "Asha", User.Role.MEMBER, true, null);
        assertEquals("ADMIN", ProjectService.roleName(admin, null));
        assertEquals("VIEWER", ProjectService.roleName(admin, ProjectMembers.Role.VIEWER));
        assertNull(ProjectService.roleName(member, null));
    }

    @Test
    void duplicateSubjectsMatchLoosely() {
        assertEquals(ProjectService.normalise("Database Management Systems"), ProjectService.normalise("  database   management-systems "));
        assertEquals("தரவுத்தள மேலாண்மை", ProjectService.normalise("தரவுத்தள_மேலாண்மை"));
        assertEquals("?, ?, ?", ProjectMembers.marks(3));
    }
}
