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

import javax.annotation.Nullable;

/**
 * The Earl of URLs
 */
public class URLs {

	private static String blobPattern;
	private static String absBlobPattern;

	/**
	 * Delegates to either {@link #absUrl(String)} or {@link #url(String)} depending on the value of
	 * {@code abs}. The path must not start with a /.
	 */
	public static String url(boolean abs, String path) {
		return abs ? absUrl(path) : url(path);
	}

	/**
	 * Returns the "absolute" URL for the given path in the Partyflow application, based on the
	 * configuration. This will be a fully qualified URL with a scheme and host.  The path must not
	 * start with a /.
	 * <p>
	 * For example, {@code api/v1/whoami} could become {@code https://example.com/partyflow/api/v1/whoami}.
	 */
	public static String absUrl(String path) {
		return publicRoot()+path;
	}

	/**
	 * Returns the relative URL for the given path in the Partyflow application, based on the
	 * configuration. This will start with a / and is relative to the domain. The path must not
	 * start with a /.
	 * <p>
	 * For example, {@code api/v1/whoami} could become {@code /partyflow/api/v1/whoami}.
	 */
	public static String url(String path) {
		return root()+path;
	}

	/**
	 * Returns the "public root" of the Partyflow application, based on the configuration. This will
	 * be a fully qualified URL with a scheme and host.
	 */
	public static String publicRoot() {
		return Partyflow.config.http.publicUrl+root();
	}

	/**
	 * Returns the "relative root" of the Partyflow application, based on the configuration. This
	 * will start with a / and is relative to the domain.
	 */
	public static String root() {
		return Partyflow.config.http.path;
	}
	
	
	static void init() {
		if (Partyflow.config.storage.publicUrlPattern.startsWith("/")) {
			blobPattern = Partyflow.config.storage.publicUrlPattern;
			absBlobPattern = Partyflow.config.http.publicUrl+Partyflow.config.storage.publicUrlPattern;
		} else if (Partyflow.config.storage.publicUrlPattern.startsWith("http://") || Partyflow.config.storage.publicUrlPattern.startsWith("https://")) {
			blobPattern = absBlobPattern = Partyflow.config.storage.publicUrlPattern;
		} else {
			blobPattern = url(Partyflow.config.storage.publicUrlPattern);
			absBlobPattern = absUrl(Partyflow.config.storage.publicUrlPattern);
		}
	}

	/**
	 * Resolve a blob path based on the storage configuration. This <i>may or may not</i> return an
	 * absolute URL with a scheme and host, depending on the config.
	 * <p>
	 * For example, these are both possible resolutions for "art/foobar.jpeg":<br>
	 * {@code /files/art/foobar.jpeg}<br>
	 * {@code https://s3.example.com/us-central-7/art/foobar.jpeg}
	 */
	public static String resolveBlob(String blob) {
		return _resolveBlob(blob, false);
	}

	/**
	 * Resolve a blob path based on the storage configuration. This <i>will always</i> return an
	 * absolute URL with a scheme and host.
	 * <p>
	 * For example, these are both possible resolutions for "art/foobar.jpeg":<br>
	 * {@code https://example.com/partyflow/files/art/foobar.jpeg}<br>
	 * {@code https://s3.example.com/us-central-7/art/foobar.jpeg}
	 */
	public static String resolveBlobAbs(String blob) {
		return _resolveBlob(blob, true);
	}

	public static String _resolveBlob(String blob, boolean abs) {
		return (abs ? absBlobPattern : blobPattern).replace("{}", blob);
	}

	/**
	 * Resolve a blob path, returning the path to the static default art if {@code art} is null.
	 * @see #resolveBlob
	 */
	public static String resolveArt(@Nullable String art) {
		return _resolveArt(art, "", false);
	}

	/**
	 * Resolve a blob path, returning the path to the static default art if {@code art} is null.
	 * The blob will have "-thumb.webp" appended, to retrieve the thumbnail instead of the full
	 * copy.
	 * @see #resolveBlob
	 */
	public static String resolveArtThumb(@Nullable String art) {
		return _resolveArt(art, "-thumb.webp", false);
	}

	/**
	 * Resolve a blob path, returning the path to the static default art if {@code art} is null.
	 * @see #resolveBlobAbs
	 */
	public static String resolveArtAbs(@Nullable String art) {
		return _resolveArt(art, "", true);
	}

	/**
	 * Resolve a blob path, returning the path to the static default art if {@code art} is null.
	 * The blob will have "-thumb.webp" appended, to retrieve the thumbnail instead of the full
	 * copy.
	 * @see #resolveBlobAbs
	 */
	public static String resolveArtThumbAbs(@Nullable String art) {
		return _resolveArt(art, "-thumb.webp", true);
	}

	private static String _resolveArt(String art, String suffix, boolean abs) {
		if (art == null) {
			return url(abs, "static/default_art.svg");
		} else {
			return resolveBlob(art+suffix);
		}
	}

}
