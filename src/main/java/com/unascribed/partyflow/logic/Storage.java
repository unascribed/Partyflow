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

package com.unascribed.partyflow.logic;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.domain.BlobBuilder;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.ContainerAccess;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.CreateContainerOptions;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.domain.Location;
import org.jclouds.io.Payload;

public final class Storage {
	
	private Storage() {}
	
	private static BlobStore delegate;
	private static String container;
	
	public static void init(BlobStore delegate, String container) {
		Storage.delegate = delegate;
		Storage.container = container;
	}

	public static BlobStoreContext getContext() {
		return delegate.getContext();
	}

	public static BlobBuilder blobBuilder(String name) {
		return delegate.blobBuilder(name);
	}

	public static boolean containerExists() {
		return delegate.containerExists(container);
	}

	public static boolean createContainerInLocation(Location location) {
		return delegate.createContainerInLocation(location, container);
	}

	public static boolean createContainerInLocation(Location location, CreateContainerOptions options) {
		return delegate.createContainerInLocation(location, container, options);
	}

	public static ContainerAccess getContainerAccess() {
		return delegate.getContainerAccess(container);
	}

	public static void setContainerAccess(ContainerAccess access) {
		delegate.setContainerAccess(container, access);
	}

	public static PageSet<? extends StorageMetadata> list() {
		return delegate.list(container);
	}

	public static PageSet<? extends StorageMetadata> list(ListContainerOptions options) {
		return delegate.list(container, options);
	}

	public static void clearContainer() {
		delegate.clearContainer(container);
	}

	public static void clearContainer(ListContainerOptions options) {
		delegate.clearContainer(container, options);
	}

	public static void deleteContainer() {
		delegate.deleteContainer(container);
	}

	public static boolean deleteContainerIfEmpty() {
		return delegate.deleteContainerIfEmpty(container);
	}

	public static boolean blobExists(String name) {
		return delegate.blobExists(container, name);
	}

	public static String putBlob(Blob blob) {
		return delegate.putBlob(container, blob);
	}

	public static String putBlob(Blob blob, PutOptions options) {
		return delegate.putBlob(container, blob, options);
	}

	public static String copyBlob(String fromContainer, String fromName, String toContainer, String toName, CopyOptions options) {
		return delegate.copyBlob(fromContainer, fromName, toContainer, toName, options);
	}

	public static BlobMetadata blobMetadata(String name) {
		return delegate.blobMetadata(container, name);
	}

	public static Blob getBlob(String name) {
		return delegate.getBlob(container, name);
	}

	public static Blob getBlob(String name, GetOptions options) {
		return delegate.getBlob(container, name, options);
	}

	public static void removeBlob(String name) {
		delegate.removeBlob(container, name);
	}

	public static void removeBlobs(Iterable<String> names) {
		delegate.removeBlobs(container, names);
	}

	public static BlobAccess getBlobAccess(String name) {
		return delegate.getBlobAccess(container, name);
	}

	public static void setBlobAccess(String name, BlobAccess access) {
		delegate.setBlobAccess(container, name, access);
	}

	public static long countBlobs(String container) {
		return delegate.countBlobs(container);
	}

	public static long countBlobs(ListContainerOptions options) {
		return delegate.countBlobs(container, options);
	}

	public static MultipartUpload initiateMultipartUpload(BlobMetadata blob, PutOptions options) {
		return delegate.initiateMultipartUpload(container, blob, options);
	}

	public static void abortMultipartUpload(MultipartUpload mpu) {
		delegate.abortMultipartUpload(mpu);
	}

	public static String completeMultipartUpload(MultipartUpload mpu, List<MultipartPart> parts) {
		return delegate.completeMultipartUpload(mpu, parts);
	}

	public static MultipartPart uploadMultipartPart(MultipartUpload mpu, int partNumber, Payload payload) {
		return delegate.uploadMultipartPart(mpu, partNumber, payload);
	}

	public static List<MultipartPart> listMultipartUpload(MultipartUpload mpu) {
		return delegate.listMultipartUpload(mpu);
	}

	public static List<MultipartUpload> listMultipartUploads(String container) {
		return delegate.listMultipartUploads(container);
	}

	public static long getMinimumMultipartPartSize() {
		return delegate.getMinimumMultipartPartSize();
	}

	public static long getMaximumMultipartPartSize() {
		return delegate.getMaximumMultipartPartSize();
	}

	public static int getMaximumNumberOfParts() {
		return delegate.getMaximumNumberOfParts();
	}

	public static void downloadBlob(String name, File destination) {
		delegate.downloadBlob(container, name, destination);
	}

	public static void downloadBlob(String name, File destination, ExecutorService executor) {
		delegate.downloadBlob(container, name, destination, executor);
	}

	public static InputStream streamBlob(String name) {
		return delegate.streamBlob(container, name);
	}

	public static InputStream streamBlob(String name, ExecutorService executor) {
		return delegate.streamBlob(container, name, executor);
	}
	
}
