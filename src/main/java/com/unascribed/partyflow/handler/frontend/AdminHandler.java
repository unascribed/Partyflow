/*
 * This file is part of Partyflow.
 *
 * Partyflow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Partyflow is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with Partyflow.
 *
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.unascribed.partyflow.handler.frontend;

import java.io.IOException;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.Get;

public class AdminHandler extends SimpleHandler implements Get {

	private static final Logger log = LoggerFactory.getLogger(AdminHandler.class);

	@Override
	public void get(String path, HttpServletRequest req, HttpServletResponse res)
			throws IOException, ServletException, SQLException {
		Session s = SessionHelper.getSessionOrThrow(req, null);
		if (!s.hasPermission("admin.administrate")) {
			res.sendError(403);
			return;
		}
	}

}
