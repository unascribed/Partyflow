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

package com.unascribed.partyflow.handler.util;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;

public class PartyflowErrorHandler extends ErrorHandler {

	private static final Logger log = LoggerFactory.getLogger("ErrorHandler");

	public record JsonError(boolean error, int code, String message, String magicString) {}
	
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
					if (HttpStatus.isRedirection(uve.getCode())) {
						int code = uve.getCode();
						String loc = uve.getMessage();
						if (code == 399) {
							code = 302;
							loc = req.getRequestURI()+"?error="+loc;
						}
						((Response)res).sendRedirect(code, loc);
						return;
					}
					res.setStatus(uve.getCode());
					if (req.getAttribute("partyflow.isApi") == Boolean.TRUE) {
						res.setStatus(uve.getCode());
						ApiHandler.serve(req, res, new JsonError(true, uve.getCode(), uve.getMessage(), null), List.of());
						return;
					} else {
						MustacheHandler.serveTemplate(req, res, "user-error.hbs.html", new Object() {
							int code = uve.getCode();
							String codeMsg = HttpStatus.getMessage(uve.getCode());
							String msg = uve.getMessage();
						});
					}
					return;
				} else {
					log.warn("An error occurred while handling a request to {}\nMagic string, for log searching: {}", baseRequest.getRequestURI(), magic, cause);
					if (req.getAttribute("partyflow.isApi") == Boolean.TRUE) {
						res.setStatus(res.getStatus());
						ApiHandler.serve(req, res, new JsonError(true, res.getStatus(), HttpStatus.getMessage(res.getStatus()), magic), List.of());
						return;
					}
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
