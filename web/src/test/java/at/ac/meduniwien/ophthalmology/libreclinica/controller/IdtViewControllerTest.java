/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

/**
 * The heritage item-data SDV listing (2026-09-29).
 *
 * <p>It compared the requested study's id with its parent's as boxed
 * {@code Integer}s, so a parent study above id 127 was treated as a site and
 * its site subjects dropped out; and it listed any study's item data to any
 * API key. Its POST, which wrote the IDT flag rows for any API key without a
 * role check or a user, had no caller and is gone.
 */
class IdtViewControllerTest {

    @Test
    void theFlagWritingPostIsGone() throws Exception {
        MockMvc mvc = ProductionMvc.standalone(new IdtViewController()).build();

        int status = mvc.perform(MockMvcRequestBuilders.post("/auth/api/itemdata/")
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andReturn().getResponse().getStatus();

        assertTrue(status == 404 || status == 405, "no handler takes the POST: " + status);
    }

    private static UserAccountBean user(int id) {
        UserAccountBean u = new UserAccountBean();
        u.setId(id);
        u.setName("monitor" + id);
        u.addUserType(UserType.USER);
        return u;
    }

    private static StudyBean study(int id, int parentId) {
        StudyBean s = new StudyBean();
        s.setId(id);
        s.setParentStudyId(parentId);
        return s;
    }

    private static StudyUserRoleBean role(int studyId, Status status) {
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setStudyId(studyId);
        r.setStatus(status);
        return r;
    }

    @Test
    void aParentStudyAboveTheIntegerCacheIsListedAsTheParentStudy() {
        assertEquals("OR", IdtViewController.studyScopeOperator(1000, 1000));
        assertEquals("OR", IdtViewController.studyScopeOperator(12, 12));
        assertEquals("AND", IdtViewController.studyScopeOperator(1001, 1000));
    }

    @Test
    void aRoleOnTheStudyOrOnTheParentOfTheSiteGrantsAccess() {
        assertTrue(IdtViewController.mayViewStudy(user(7), study(1000, 0), List.of(role(1000, Status.AVAILABLE))));
        assertTrue(IdtViewController.mayViewStudy(user(7), study(1001, 1000), List.of(role(1000, Status.AVAILABLE))));
        assertTrue(IdtViewController.mayViewStudy(user(7), study(1001, 1000), List.of(role(1001, Status.AVAILABLE))));
    }

    @Test
    void aRoleOnlyOnASiteDoesNotOpenTheWholeStudy() {
        assertFalse(IdtViewController.mayViewStudy(user(7), study(1000, 0), List.of(role(1001, Status.AVAILABLE))));
    }

    @Test
    void noLiveRoleNoAccess() {
        assertFalse(IdtViewController.mayViewStudy(user(7), study(1000, 0), List.of()));
        assertFalse(IdtViewController.mayViewStudy(user(7), study(1000, 0), List.of(role(2000, Status.AVAILABLE))));
        assertFalse(IdtViewController.mayViewStudy(user(7), study(1000, 0), List.of(role(1000, Status.DELETED))));
        assertFalse(IdtViewController.mayViewStudy(user(7), study(1000, 0), List.of(role(1000, Status.AUTO_DELETED))));
    }

    @Test
    void aSystemAdministratorMayViewAnyStudy() {
        UserAccountBean admin = user(1);
        admin.addUserType(UserType.SYSADMIN);
        assertTrue(IdtViewController.mayViewStudy(admin, study(1000, 0), List.of()));
    }

    @Test
    void noUserOrNoStudyIsRefused() {
        assertFalse(IdtViewController.mayViewStudy(null, study(1000, 0), List.of(role(1000, Status.AVAILABLE))));
        assertFalse(IdtViewController.mayViewStudy(new UserAccountBean(), study(1000, 0), List.of(role(1000, Status.AVAILABLE))));
        assertFalse(IdtViewController.mayViewStudy(user(7), null, List.of(role(0, Status.AVAILABLE))));
        assertFalse(IdtViewController.mayViewStudy(user(7), new StudyBean(), List.of(role(0, Status.AVAILABLE))));
    }
}
