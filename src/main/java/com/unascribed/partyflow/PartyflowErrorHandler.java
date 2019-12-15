package com.unascribed.partyflow;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.handler.MustacheHandler;

public class PartyflowErrorHandler extends ErrorHandler {

	private static final Logger log = LoggerFactory
			.getLogger("ErrorHandler");

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest req, HttpServletResponse res)
			throws IOException, ServletException {
		String template = "error.hbs.html";
		if (res.getStatus() == 404 || res.getStatus() == 410) {
			template = "blackhole.hbs.html";
		} else if (HttpStatus.isServerError(res.getStatus())) {
			template = "internal-error.hbs.html";
		}
		String magic = Partyflow.randomString(16);
		Throwable cause = (Throwable)req.getAttribute(Dispatcher.ERROR_EXCEPTION);
		if (cause != null) {
			log.warn("An error occurred while handling a request to {}\nMagic string, for log searching: {}", baseRequest.getRequestURI(), magic, cause);
		}
		MustacheHandler.serveTemplate(req, res, template, new Object() {
			int code = res.getStatus();
			String msg = HttpStatus.getMessage(res.getStatus());
			String magic_string = magic;
			boolean exception = cause != null;
		});
	}

}
