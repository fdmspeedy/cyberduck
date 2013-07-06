package ch.cyberduck.core;

/*
 *  Copyright (c) 2005 David Kocher. All rights reserved.
 *  http://cyberduck.ch/
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Bug fixes, suggestions and comments should be sent to:
 *  dkocher@cyberduck.ch
 */

import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.ConnectionCanceledException;
import ch.cyberduck.core.exception.DefaultIOExceptionMappingService;
import ch.cyberduck.core.io.BandwidthThrottle;
import ch.cyberduck.core.io.IOResumeException;
import ch.cyberduck.core.io.ThrottledInputStream;
import ch.cyberduck.core.io.ThrottledOutputStream;
import ch.cyberduck.core.local.Local;
import ch.cyberduck.core.local.LocalFactory;
import ch.cyberduck.core.local.TemporaryFileServiceFactory;
import ch.cyberduck.core.serializer.Deserializer;
import ch.cyberduck.core.serializer.DeserializerFactory;
import ch.cyberduck.core.serializer.Serializer;
import ch.cyberduck.core.serializer.SerializerFactory;
import ch.cyberduck.core.transfer.TransferAction;
import ch.cyberduck.core.transfer.TransferOptions;
import ch.cyberduck.core.transfer.TransferPrompt;
import ch.cyberduck.core.transfer.TransferStatus;
import ch.cyberduck.core.transfer.upload.UploadTransfer;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @version $Id$
 */
public abstract class Path extends AbstractPath implements Serializable {
    private static final Logger log = Logger.getLogger(Path.class);

    /**
     * The absolute remote path
     */
    private String path;

    /**
     * Reference to the parent
     */
    protected Path parent;

    /**
     * The local path to be used if file is copied
     */
    private Local local;

    /**
     * An absolute reference here the symbolic link is pointing to
     */
    private Path symlink;

    /**
     * Attributes denoting this path
     */
    private PathAttributes attributes;

    protected <T> Path(final Session session, T serialized) {
        final Deserializer dict = DeserializerFactory.createDeserializer(serialized);
        String pathObj = dict.stringForKey("Remote");
        if(pathObj != null) {
            this.setPath(session, pathObj);
        }
        final String localObj = dict.stringForKey("Local");
        if(localObj != null) {
            this.local = LocalFactory.createLocal(localObj);
        }
        final Object symlinkObj = dict.objectForKey("Symbolic Link");
        if(symlinkObj != null) {
            this.symlink = PathFactory.createPath(session, symlinkObj);
        }
        final Object attributesObj = dict.objectForKey("Attributes");
        if(attributesObj != null) {
            this.attributes = new PathAttributes(attributesObj);
        }
        else {
            this.attributes = new PathAttributes(Path.FILE_TYPE);
        }
    }

    @Override
    public <T> T getAsDictionary() {
        final Serializer dict = SerializerFactory.createSerializer();
        return this.getAsDictionary(dict);
    }

    protected <S> S getAsDictionary(Serializer dict) {
        dict.setStringForKey(this.getAbsolute(), "Remote");
        if(local != null) {
            dict.setStringForKey(local.getAbsolute(), "Local");
        }
        if(symlink != null) {
            dict.setObjectForKey(symlink, "Symbolic Link");
        }
        dict.setObjectForKey(attributes, "Attributes");
        return dict.getSerialized();
    }

    /**
     * A remote path where nothing is known about a local equivalent.
     *
     * @param parent the absolute directory
     * @param name   the file relative to param path
     * @param type   File type
     */
    public Path(final Path parent, final String name, final int type) {
        this.setPath(parent, name);
        this.attributes = new PathAttributes(type);
    }

    /**
     * A remote path where nothing is known about a local equivalent.
     *
     * @param absolute The absolute path of the remote file
     * @param type     File type
     */
    public Path(final Session session, final String absolute, final int type) {
        this.setPath(session, absolute);
        this.attributes = new PathAttributes(type);
    }

    /**
     * Create a new path where you know the local file already exists
     * and the remote equivalent might be created later.
     * The remote filename will be extracted from the local file.
     *
     * @param parent The absolute path to the parent directory on the remote host
     * @param file   The associated local file
     */
    public Path(final Path parent, final Local file) {
        this.setPath(parent, file.getName());
        this.setLocal(file);
        this.attributes = new PathAttributes(local.attributes().isDirectory() ? DIRECTORY_TYPE : FILE_TYPE);
    }

    private void setPath(final Session session, final String absolute) {
        if(absolute.equals(String.valueOf(Path.DELIMITER))) {
            this.setPath((Path) null, Path.getName(PathNormalizer.normalize(absolute, true)));
        }
        else {
            final Path parent = PathFactory.createPath(session, Path.getParent(PathNormalizer.normalize(absolute, true), Path.DELIMITER),
                    Path.DIRECTORY_TYPE);
            if(parent.isRoot()) {
                parent.attributes().setType(Path.VOLUME_TYPE | Path.DIRECTORY_TYPE);
            }
            this.setPath(parent,
                    Path.getName(PathNormalizer.normalize(absolute, true)));
        }
    }

    /**
     * @param parent The parent directory
     * @param name   The filename
     */
    public void setPath(final Path parent, final String name) {
        this.parent = parent;
        if(null == parent) {
            this.path = name;
        }
        else {
            if(parent.isRoot()) {
                this.path = parent.getAbsolute() + name;
            }
            else {
                this.path = parent.getAbsolute() + Path.DELIMITER + name;
            }
        }
    }

    @Override
    public String unique() {
        if(StringUtils.isNotBlank(this.attributes().getRegion())) {
            return String.format("%s-%s", super.unique(), this.attributes().getRegion());
        }
        return super.unique();
    }

    /**
     * The path delimiter for remote paths
     */
    public static final char DELIMITER = '/';

    @Override
    public char getPathDelimiter() {
        return String.valueOf(DELIMITER).charAt(0);
    }

    /**
     * @return True if this path denotes a container
     */
    public boolean isContainer() {
        return this.isRoot();
    }

    /**
     * @return Default path or root with volume attributes set
     */
    public Path getContainer() {
        Path container = this;
        while(!container.isContainer()) {
            container = container.getParent();
        }
        return container;
    }

    public Path getParent() {
        if(this.isRoot()) {
            return this;
        }
        return parent;
    }

    /**
     * @return The session this path uses to send commands
     */
    public abstract Session<?> getSession();

    /**
     * Default implementation returning a reference to self. You can override this
     * if you need a different strategy to compare hashcode and equality for caching
     * in a model.
     *
     * @return Reference to the path to be used in table models an file listing cache.
     * @see ch.cyberduck.core.Cache#lookup(PathReference)
     */
    @Override
    public PathReference getReference() {
        return PathReferenceFactory.createPathReference(this);
    }

    @Override
    public PathAttributes attributes() {
        return attributes;
    }

    public void setAttributes(final PathAttributes attributes) {
        this.attributes = attributes;
    }

    /**
     * @return the path relative to its parent directory
     */
    @Override
    public String getName() {
        if(this.isRoot()) {
            return String.valueOf(DELIMITER);
        }
        final String abs = this.getAbsolute();
        int index = abs.lastIndexOf(DELIMITER);
        return abs.substring(index + 1);
    }

    public String getKey() {
        return this.getAbsolute();
    }

    /**
     * @return the absolute path name, e.g. /home/user/filename
     */
    @Override
    public String getAbsolute() {
        return path;
    }

    /**
     * Set the local equivalent of this path
     *
     * @param file Send <code>null</code> to reset the local path to the default value
     */
    public void setLocal(final Local file) {
        this.local = file;
    }

    /**
     * @return The local alias of this path
     */
    public Local getLocal() {
        return local;
    }

    public void setSymlinkTarget(final Path name) {
        this.symlink = name;
    }

    /**
     * @return The target of the symbolic link if this path denotes a symbolic link
     * @see ch.cyberduck.core.PathAttributes#isSymbolicLink
     */
    public Path getSymlinkTarget() {
        final PathAttributes attributes = this.attributes();
        if(attributes.isSymbolicLink()) {
            return symlink;
        }
        return null;
    }

    public abstract void mkdir() throws BackgroundException;

    public abstract AttributedList<Path> list() throws BackgroundException;

    /**
     * @param renamed Must be an absolute path
     */
    public abstract void rename(Path renamed) throws BackgroundException;

    /**
     * Upload an empty file.
     */
    public boolean touch() throws BackgroundException {
        final Local temp = TemporaryFileServiceFactory.get().create(this);
        temp.touch();
        this.setLocal(temp);
        TransferOptions options = new TransferOptions();
        UploadTransfer upload = new UploadTransfer(this);
        try {
            upload.start(new TransferPrompt() {
                @Override
                public TransferAction prompt() throws BackgroundException {
                    return TransferAction.ACTION_OVERWRITE;
                }
            }, options);
        }
        finally {
            temp.delete();
            this.setLocal(null);
        }
        return upload.isComplete();
    }

    /**
     * Remove this file from the remote host. Does not affect any corresponding local file
     *
     * @param prompt Login prompt for multi factor authentication
     */
    public abstract void delete(final LoginController prompt) throws BackgroundException;

    /**
     * @param status Transfer status
     * @return Stream to read from to download file
     */
    public abstract InputStream read(final TransferStatus status) throws BackgroundException;

    /**
     * @param throttle The bandwidth limit
     * @param listener The stream listener to notify about bytes received and sent
     * @param status   Transfer status
     */
    public abstract void download(BandwidthThrottle throttle, StreamListener listener,
                                  TransferStatus status) throws BackgroundException;

    /**
     * @param status Transfer status
     * @return Stream to write to for upload
     */
    public abstract OutputStream write(TransferStatus status) throws BackgroundException;

    /**
     * @param throttle The bandwidth limit
     * @param listener The stream listener to notify about bytes received and sent
     * @param status   Transfer status
     */
    public abstract void upload(BandwidthThrottle throttle, StreamListener listener,
                                TransferStatus status) throws BackgroundException;

    /**
     * @param out      Remote stream
     * @param in       Local stream
     * @param throttle The bandwidth limit
     * @param l        Listener for bytes sent
     * @param status   Transfer status
     * @throws IOException Write not completed due to a I/O problem
     */
    protected void upload(final OutputStream out, final InputStream in,
                          final BandwidthThrottle throttle,
                          final StreamListener l, final TransferStatus status) throws IOException, ConnectionCanceledException {
        this.upload(out, in, throttle, l, status.getCurrent(), -1, status);
    }

    /**
     * Will copy from in to out. Will attempt to skip Status#getCurrent
     * from the inputstream but not from the outputstream. The outputstream
     * is asssumed to append to a already existing file if
     * Status#getCurrent > 0
     *
     * @param out      The stream to write to
     * @param in       The stream to read from
     * @param throttle The bandwidth limit
     * @param l        The stream listener to notify about bytes received and sent
     * @param offset   Start reading at offset in file
     * @param limit    Transfer only up to this length
     * @param status   Transfer status
     * @throws IOResumeException If the input stream fails to skip the appropriate
     *                           number of bytes
     * @throws IOException       Write not completed due to a I/O problem
     */
    protected void upload(final OutputStream out, final InputStream in, final BandwidthThrottle throttle,
                          final StreamListener l, long offset, final long limit, final TransferStatus status) throws IOException, ConnectionCanceledException {
        if(log.isDebugEnabled()) {
            log.debug("upload(" + out.toString() + ", " + in.toString());
        }
        if(offset > 0) {
            long skipped = in.skip(offset);
            if(log.isInfoEnabled()) {
                log.info(String.format("Skipping %d bytes", skipped));
            }
            if(skipped < status.getCurrent()) {
                throw new IOResumeException(String.format("Skipped %d bytes instead of %d",
                        skipped, status.getCurrent()));
            }
        }
        this.transfer(in, new ThrottledOutputStream(out, throttle), l, limit, status);
    }

    /**
     * Will copy from in to out. Does not attempt to skip any bytes from the streams.
     *
     * @param in       The stream to read from
     * @param out      The stream to write to
     * @param throttle The bandwidth limit
     * @param l        The stream listener to notify about bytes received and sent
     * @param status   Transfer status
     * @throws IOException Write not completed due to a I/O problem
     */
    protected void download(final InputStream in, final OutputStream out, final BandwidthThrottle throttle,
                            final StreamListener l, final TransferStatus status) throws IOException, ConnectionCanceledException {
        if(log.isDebugEnabled()) {
            log.debug("download(" + in.toString() + ", " + out.toString());
        }
        this.transfer(new ThrottledInputStream(in, throttle), out, l, -1, status);
    }

    /**
     * Updates the current number of bytes transferred in the status reference.
     *
     * @param in       The stream to read from
     * @param out      The stream to write to
     * @param listener The stream listener to notify about bytes received and sent
     * @param limit    Transfer only up to this length
     * @param status   Transfer status
     * @throws IOException Write not completed due to a I/O problem
     */
    protected void transfer(final InputStream in, final OutputStream out,
                            final StreamListener listener, final long limit,
                            final TransferStatus status) throws IOException, ConnectionCanceledException {
        final BufferedInputStream bi = new BufferedInputStream(in);
        final BufferedOutputStream bo = new BufferedOutputStream(out);
        try {
            final int chunksize = Preferences.instance().getInteger("connection.chunksize");
            final byte[] chunk = new byte[chunksize];
            long bytesTransferred = 0;
            while(!status.isCanceled()) {
                final int read = bi.read(chunk, 0, chunksize);
                if(-1 == read) {
                    if(log.isDebugEnabled()) {
                        log.debug("End of file reached");
                    }
                    // End of file
                    status.setComplete();
                    break;
                }
                else {
                    status.addCurrent(read);
                    listener.bytesReceived(read);
                    bo.write(chunk, 0, read);
                    listener.bytesSent(read);
                    bytesTransferred += read;
                    if(limit == bytesTransferred) {
                        if(log.isDebugEnabled()) {
                            log.debug(String.format("Limit %d reached reading from stream", limit));
                        }
                        // Part reached
                        if(0 == bi.available()) {
                            // End of file
                            status.setComplete();
                        }
                        break;
                    }
                }
            }
        }
        finally {
            bo.flush();
        }
        if(status.isCanceled()) {
            throw new ConnectionCanceledException();
        }
    }

    /**
     * Default implementation using a temporary file on localhost as an intermediary
     * with a download and upload transfer.
     *
     * @param copy     Destination
     * @param throttle The bandwidth limit
     * @param listener Callback
     * @param status   Transfer status
     */
    public void copy(final Path copy, final BandwidthThrottle throttle,
                     final StreamListener listener, final TransferStatus status) throws BackgroundException {
        InputStream in = null;
        OutputStream out = null;
        try {
            if(this.attributes().isFile()) {
                this.transfer(in = new ThrottledInputStream(this.read(status), throttle),
                        out = new ThrottledOutputStream(copy.write(status), throttle),
                        listener, -1, status);
            }
        }
        catch(IOException e) {
            throw new DefaultIOExceptionMappingService().map("Cannot copy {0}", e, this);
        }
        finally {
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(out);
        }
    }

    /**
     * Check for file existence. The default implementation does a directory listing of the parent folder.
     *
     * @return True if the path is cached.
     */
    public boolean exists() throws BackgroundException {
        if(this.isRoot()) {
            return true;
        }
        return this.getParent().list().contains(this.getReference());
    }

    /**
     * @return The hashcode of #getAbsolute()
     * @see #getAbsolute()
     */
    @Override
    public int hashCode() {
        return this.getReference().hashCode();
    }

    /**
     * @param other Path to compare with
     * @return true if the other path has the same absolute path name
     */
    @Override
    public boolean equals(Object other) {
        if(null == other) {
            return false;
        }
        if(other instanceof Path) {
            return this.getReference().equals(((Path) other).getReference());
        }
        return false;
    }

    /**
     * @return The absolute path name
     */
    @Override
    public String toString() {
        return this.getAbsolute();
    }

    /**
     * @param directory Parent directory
     * @return True if this is a child in the path hierarchy of the argument passed
     */
    public boolean isChild(final Path directory) {
        if(directory.attributes().isFile()) {
            // If a file we don't have any children at all
            return false;
        }
        if(this.isRoot()) {
            // Root cannot be a child of any other path
            return false;
        }
        if(directory.isRoot()) {
            // Any other path is a child
            return true;
        }
        if(ObjectUtils.equals(this.getParent(), directory.getParent())) {
            // Cannot be a child if the same parent
            return false;
        }
        for(Path parent = this.getParent(); !parent.isRoot(); parent = parent.getParent()) {
            if(parent.equals(directory)) {
                return true;
            }
        }
        return false;
    }
}