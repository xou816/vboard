/*
 * This file is part of the vboard distribution.
 * (https://github.com/voyages-sncf-technologies/vboard)
 * Copyright (c) 2017 VSCT.
 *
 * vboard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3.
 *
 * vboard is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.vsct.vboard.controllers;

import com.vsct.vboard.DAO.UserDAO;
import com.vsct.vboard.config.AdministratorsConfig;
import com.vsct.vboard.models.Role;
import com.vsct.vboard.models.User;
import com.vsct.vboard.models.VBoardException;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.representations.IDToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;


@RestController
@RequestMapping(value = "/authentication")
public class AuthenticationController {

    private static final User ANONYMOUS_USER = new User("@nonymous", "Anonymous", "User");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final UserDAO userDAO;
    private final AdministratorsConfig administratorsConfig;
    private final HttpSession session;

    @Autowired
    public AuthenticationController(UserDAO userDAO, AdministratorsConfig administratorsConfig, HttpSession session) {
        this.userDAO = userDAO;
        this.administratorsConfig = administratorsConfig;
        this.session = session;
    }

    @RequestMapping(value = "/login", method = RequestMethod.GET)
    @ResponseBody
    @Valid
    public String initializeUser() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getPrincipal())) {
            try {
                this.session.setAttribute("User", ANONYMOUS_USER);
            } catch (IllegalStateException e) {
                this.logger.error("Could not set attribute User in current session: {}", e);
            }
            return ANONYMOUS_USER.toString();
        }
        final KeycloakPrincipal userDetails = (KeycloakPrincipal) auth.getPrincipal();
        final IDToken idToken = userDetails.getKeycloakSecurityContext().getToken();
        final boolean isAdmin = this.administratorsConfig.getEmails().contains(idToken.getEmail());
        final User user = this.userDAO.findByEmail(idToken.getEmail());
        if (user != null) { // User exists in DB, moving on!
            user.setIsAdmin(isAdmin);
            this.session.setAttribute("User", user);
            return user.toString();
        }
        // We don't know this user, so we fetch information from Keycloak tokens
        final User kcUser = new User(idToken.getEmail(), idToken.getGivenName(), idToken.getFamilyName());
        kcUser.setIsAdmin(isAdmin);
        this.userDAO.save(kcUser);
        this.session.setAttribute("User", kcUser);
        return kcUser.toString();
    }

    @RequestMapping(value = "/logout", method = RequestMethod.POST)
    public void logout() {
        session.removeAttribute("User");
    }

    public @NotNull User getSessionUser() {
        try {
            final User user = (User) session.getAttribute("User");
            if (user == null) {
                return ANONYMOUS_USER;
            }
            return user;
        } catch (IllegalStateException e) {
            return ANONYMOUS_USER;
        }
    }

    public @NotNull User getSessionUserWithSyncFromDB() {
        final User sessionUser = this.getSessionUser();
        if (ANONYMOUS_USER.equals(sessionUser)) {
            return ANONYMOUS_USER;
        }
        final User dbUser = this.userDAO.findByEmail(sessionUser.getEmail());
        if (dbUser == null) {
            throw new VBoardException("No user found in DB for email=" + sessionUser.getEmail());
        }
        if (!dbUser.equals(this.getSessionUser())) {
            this.logger.debug("Updating user in session cache");
            this.session.setAttribute("User", dbUser);
        }
        return dbUser;
    }

    public void ensureCurrentUserIsAdmin() {
        if (!this.getSessionUser().isAdmin()) {
            throw new VBoardException("Unauthorized Access - Current user is not an admin");
        }
    }

    public void ensureUserHasNewsletterRole() {
        if (!this.getSessionUserWithSyncFromDB().hasRole(Role.Newsletter)) {
            throw new VBoardException("Unauthorized Access - Current user does not have the 'Newsletter' role");
        }
    }

    public void ensureEmailMatchesSessionUser(String email) {
        final String sessionUserEmail = this.getSessionUser().getEmail();
        if (!sessionUserEmail.equals(email)) {
            throw new VBoardException("Unauthorized Access - The user given by the frontend is not the one that is given by the backend Front-end user=" + email + ", backend-user=" + sessionUserEmail);
        }
    }

    // Check whether the user (Format string) is the same as the session user String
    public void ensureNewEntityAuthorMatchesSessionUser(String author) {
        final String userString = this.getSessionUser().getUserString();
        if (!userString.equals(author)) {
            throw new VBoardException("Unauthorized Access - The user provided by the frontend does not match the current session: Front-end user=" + author + ", backend-user=" + userString);
        }
    }

    // Check whether the user has the authorization to do that action (the author or an admins)
    public void ensureUserHasRightsToAlterPin(String pinAuthor) {
        final User sessionUser = this.getSessionUser();
        final String userString = sessionUser.getUserString();
        if(!(userString.equals(pinAuthor) || sessionUser.isAdmin() || hasModeratorRole())) {
            throw new VBoardException("Unauthorized Access - User cannot update nor delete pins: " + userString);
        }
    }

    // Check whether the user has the authorization to do that action (the author or an admins)
    public void ensureUserHasRightsToAlterComment(String commentAuthor) {
        final User sessionUser = this.getSessionUser();
        final String userString = sessionUser.getUserString();
        if(!(userString.equals(commentAuthor) || sessionUser.isAdmin() || this.getSessionUser().getEmail().equals(commentAuthor) || hasModeratorRole())) {
            throw new VBoardException("Unauthorized Access - The user does not have the authorization to do that action(" + userString + ")");
        }
    }

    private boolean hasModeratorRole() {
        return this.getSessionUserWithSyncFromDB().hasRole(Role.Moderateur);
    }

}
