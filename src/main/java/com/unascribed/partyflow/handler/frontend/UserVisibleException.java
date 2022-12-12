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

import jakarta.servlet.ServletException;

import org.eclipse.jetty.http.HttpStatus;

public class UserVisibleException extends ServletException {

	private final int code;
	private final String message;

	public UserVisibleException(int code) {
		super(code+" - "+HttpStatus.getMessage(code));
		this.code = code;
		this.message = HttpStatus.getMessage(code);
	}
	
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
