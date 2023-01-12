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

package com.unascribed.partyflow.data;

import java.sql.SQLException;

import com.lambdaworks.crypto.SCrypt;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.util.Queries;
import com.unascribed.partyflow.logic.UserRole;

import com.google.common.math.IntMath;

public class QUsers extends Queries {

	private static String DUMMY_PASSWORD;

	static {
		new Thread(() -> {
			String s = scrypt("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e");
			synchronized (QUsers.class) {
				DUMMY_PASSWORD = s;
				QUsers.class.notifyAll();
			}
		}, "Dummy compute thread").start();
	}

	public static void create(String name, String username, String passwordSha512, UserRole role) throws SQLException {
		update("INSERT INTO `users` (`username`, `display_name`, `password`, `role`, `created_at`) "
					+ "VALUES (?, ?, ?, ?, NOW());",
				username, name, scrypt(passwordSha512), role.id());
	}

	private static String scrypt(String passwordSha512) {
		var cfg = Partyflow.config.security.scrypt;
		return SCrypt.scrypt(passwordSha512,
				IntMath.pow(2, cfg.cpu), cfg.memory, cfg.parallelization);
	}

	public static void updateLastLogin(int userId) throws SQLException {
		update("UPDATE `users` SET `last_login` = NOW() WHERE `user_id` = ?;",
				userId);
	}
	
	public static AuthenticableUser findForAuth(String slug) throws SQLException {
		try (var rs = select("SELECT `user_id`, `password` FROM `users` WHERE `username` = ?;",
				slug)) {
			if (rs.first()) {
				return new AuthenticableUser(rs.getInt("user_id"), rs.getString("password"));
			} else {
				return AuthenticableUser.DUMMY;
			}
		}
	}
	
	public record AuthenticableUser(int userId, String password) {
		
		public static final AuthenticableUser DUMMY = new AuthenticableUser(-1, null);
		
		public boolean verify(String passwordSha512) {
			synchronized (QUsers.class) {
				while (DUMMY_PASSWORD == null) {
					try {
						QUsers.class.wait();
					} catch (InterruptedException e) {}
				}
			}
			String p = (password == null ? DUMMY_PASSWORD : password);
			return SCrypt.check(passwordSha512, p);
		}
		
	}

}
