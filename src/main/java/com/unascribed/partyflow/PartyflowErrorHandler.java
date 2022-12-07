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

package com.unascribed.partyflow;

import java.io.IOException;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.handler.MustacheHandler;
import com.unascribed.partyflow.handler.UserVisibleException;

public class PartyflowErrorHandler extends ErrorHandler {

	private static final Logger log = LoggerFactory.getLogger("ErrorHandler");

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest req, HttpServletResponse res)
			throws IOException, ServletException {
		try {
			String template = "error.hbs.html";
			if (res.getStatus() == 404 || res.getStatus() == 410) {
				template = "blackhole.hbs.html";
			} else if (HttpStatus.isServerError(res.getStatus())) {
				template = "internal-error.hbs.html";
			}
			String magic = Partyflow.randomString(16);
			Throwable cause = (Throwable)req.getAttribute(Dispatcher.ERROR_EXCEPTION);
			if (cause != null) {
				UserVisibleException uve;
				if (cause instanceof UserVisibleException) {
					uve = (UserVisibleException)cause;
				} else if (cause.getCause() instanceof UserVisibleException) {
					uve = (UserVisibleException)cause.getCause();
				} else {
					uve = null;
				}
				if (uve != null) {
					res.setStatus(uve.getCode());
					MustacheHandler.serveTemplate(req, res, "user-error.hbs.html", new Object() {
						int code = uve.getCode();
						String codeMsg = HttpStatus.getMessage(uve.getCode());
						String msg = uve.getMessage();
					});
					return;
				} else {
					log.warn("An error occurred while handling a request to {}\nMagic string, for log searching: {}", baseRequest.getRequestURI(), magic, cause);
				}
			}
			MustacheHandler.serveTemplate(req, res, template, new Object() {
				int code = res.getStatus();
				String msg = HttpStatus.getMessage(res.getStatus());
				String magic_string = magic;
				boolean exception = cause != null;
			});
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

}
