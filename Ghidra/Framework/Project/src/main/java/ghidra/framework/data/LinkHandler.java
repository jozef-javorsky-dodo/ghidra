/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.framework.data;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.Icon;

import generic.theme.GIcon;
import ghidra.framework.model.*;
import ghidra.framework.protocol.ghidra.*;
import ghidra.framework.store.FileSystem;
import ghidra.framework.store.FolderItem;
import ghidra.framework.store.local.LocalFileSystem;
import ghidra.util.InvalidNameException;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;

/**
 * NOTE:  ALL ContentHandler implementations MUST END IN "ContentHandler".  If not,
 * the ClassSearcher will not find them.
 * 
 * <code>LinkHandler</code> defines an application interface for handling domain files which are
 * shortcut links to another supported content type.
 * 
 * @param <T> {@link URLLinkObject} implementation class
 */
public abstract class LinkHandler<T extends DomainObjectAdapterDB> extends DBContentHandler<T> {

	public static final String URL_METADATA_KEY = "link.url";

	// 16x16 link icon where link is placed in lower-left corner
	public static final Icon LINK_ICON = new GIcon("icon.content.handler.link.overlay");

	/**
	 * Create a link file using the specified URL
	 * @param ghidraUrl link URL (must be a Ghidra URL - see {@link GhidraURL}).
	 * @param fs filesystem where link file should be created 
	 * @param folderPath folder path which should contain link file
	 * @param linkFilename link filename
	 * @throws IOException if an IO error occurs
	 * @throws InvalidNameException if invalid folderPath or linkFilename specified
	 */
	protected final void createLink(URL ghidraUrl, LocalFileSystem fs, String folderPath,
			String linkFilename) throws IOException, InvalidNameException {
		URLLinkObject link = new URLLinkObject(linkFilename, ghidraUrl, this);
		try {
			createFile(fs, null, folderPath, linkFilename, link, TaskMonitor.DUMMY);
		}
		catch (CancelledException e) {
			throw new AssertException(e); // won't happen
		}
		finally {
			link.release(this);
		}
	}

	@Override
	public final T getReadOnlyObject(FolderItem item, int version, boolean okToUpgrade,
			Object consumer, TaskMonitor monitor)
			throws IOException, VersionException, CancelledException {
		if (!okToUpgrade) {
			throw new UnsupportedOperationException("okToUpgrade must be true for link-file");
		}
		return getObject(item, version, consumer, monitor, false);
	}

	@Override
	public T getImmutableObject(FolderItem item, Object consumer, int version, int minChangeVersion,
			TaskMonitor monitor) throws IOException, CancelledException, VersionException {
		if (minChangeVersion != -1) {
			throw new UnsupportedOperationException("minChangeVersion must be -1 for link-file");
		}
		return getObject(item, version, consumer, monitor, true);
	}

	private T getObject(FolderItem item, int version, Object consumer, TaskMonitor monitor,
			boolean immutable) throws IOException, VersionException, CancelledException {

		URL ghidraUrl = getURL(item);

		Class<?> domainObjectClass = getDomainObjectClass();
		if (domainObjectClass == null) {
			throw new UnsupportedOperationException("");
		}

		AtomicReference<VersionException> verExcRef = new AtomicReference<>();
		AtomicReference<T> domainObjectRef = new AtomicReference<>();
		GhidraURLQuery.queryUrl(ghidraUrl, new GhidraURLResultHandlerAdapter(true) {

			@Override
			public void processResult(DomainFile domainFile, URL url, TaskMonitor m)
					throws IOException, CancelledException {
				if (!getDomainObjectClass().isAssignableFrom(domainFile.getDomainObjectClass())) {
					throw new BadLinkException("Expected " + getDomainObjectClass() +
						" but linked to " + domainFile.getDomainObjectClass());
				}
				try {
					@SuppressWarnings("unchecked")
					T linkedObject = immutable
							? (T) domainFile.getImmutableDomainObject(consumer, version, monitor)
							: (T) domainFile.getReadOnlyDomainObject(consumer, version, monitor);
					domainObjectRef.set(linkedObject);
				}
				catch (VersionException e) {
					verExcRef.set(e);
				}
			}

			@Override
			public void handleUnauthorizedAccess(URL url) throws IOException {
				throw new IOException("Authorization failure");
			}
		}, monitor);

		VersionException versionException = verExcRef.get();
		if (versionException != null) {
			throw versionException;
		}

		T domainObj = domainObjectRef.get();
		if (domainObj == null) {
			throw new IOException(
				"Failed to obtain linked object for unknown reason: " + item.getPathName());
		}
		return domainObj;
	}

	@Override
	public final T getDomainObject(FolderItem item, FileSystem userfs, long checkoutId,
			boolean okToUpgrade, boolean okToRecover, Object consumer, TaskMonitor monitor)
			throws IOException, CancelledException, VersionException {
		// getReadOnlyObject or getImmutableObject should be used
		throw new UnsupportedOperationException("link-file does not support getDomainObject");
	}

	@Override
	public final ChangeSet getChangeSet(FolderItem versionedFolderItem, int olderVersion,
			int newerVersion) throws VersionException, IOException {
		return null;
	}

	@Override
	public final DomainObjectMergeManager getMergeManager(DomainObject resultsObj,
			DomainObject sourceObj, DomainObject originalObj, DomainObject latestObj) {
		return null;
	}

	@Override
	public final boolean isPrivateContentType() {
		throw new UnsupportedOperationException("Link file requires checking server vs local URL");
	}

	/**
	 * Get the link URL which corresponds to the specified link file.
	 * See {@link DomainFile#isLinkFile()}.
	 * @param linkFile link-file domain file
	 * @return link URL
	 * @throws MalformedURLException if link is bad or unsupported.
	 * @throws IOException if IO error or supported link file not specified
	 */
	public static URL getURL(DomainFile linkFile) throws IOException {
		String contentType = linkFile.getContentType();
		ContentHandler<?> ch = DomainObjectAdapter.getContentHandler(contentType);
		if (ch instanceof LinkHandler) {
			Map<String, String> metadata = linkFile.getMetadata();
			String urlStr = metadata.get(URL_METADATA_KEY);
			if (urlStr != null) {
				return new URL(urlStr);
			}
		}
		throw new IOException("Invalid link file: " + contentType);
	}

	/**
	 * Get the link URL which corresponds to the specified link file.
	 * See {@link DomainFile#isLinkFile()}.
	 * @param linkFile link-file folder item
	 * @return link URL
	 * @throws MalformedURLException if link is bad or unsupported.
	 * @throws IOException if IO error or supported link file not specified
	 */
	static URL getURL(FolderItem linkFile) throws IOException {

		String contentType = linkFile.getContentType();
		ContentHandler<?> ch = DomainObjectAdapter.getContentHandler(contentType);
		if (ch instanceof LinkHandler) {
			Map<String, String> metadata = GhidraFileData.getMetadata(linkFile);
			String urlStr = metadata.get(URL_METADATA_KEY);
			if (urlStr != null) {
				return new URL(urlStr);
			}
		}
		throw new IOException("Invalid link file: " + contentType);
	}

	/**
	 * Get the base icon for this link-file which does not include the 
	 * link overlay icon.
	 */
	@Override
	abstract public Icon getIcon();

}
