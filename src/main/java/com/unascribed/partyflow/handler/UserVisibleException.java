package com.unascribed.partyflow.handler;

import jakarta.servlet.ServletException;

import org.eclipse.jetty.http.HttpStatus;

public class UserVisibleException extends ServletException {

	private final int code;
	private final String message;

	public UserVisibleException(int code, String message) {
		super(code+" - "+HttpStatus.getMessage(code)+" ("+message+")");
		this.code = code;
		this.message = message;
	}

	public int getCode() {
		return code;
	}

	@Override
	public String getMessage() {
		return message;
	}

}
