/*
 * Copyright (c) 1994, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.io;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.util.Set;

import com.jetbrains.internal.IoOverNio;
import jdk.internal.access.SharedSecrets;
import jdk.internal.access.JavaIOFileDescriptorAccess;
import jdk.internal.event.FileWriteEvent;
import sun.nio.ch.FileChannelImpl;
import static com.jetbrains.internal.IoOverNio.DEBUG;


/**
 * A file output stream is an output stream for writing data to a
 * {@code File} or to a {@code FileDescriptor}. Whether or not
 * a file is available or may be created depends upon the underlying
 * platform.  Some platforms, in particular, allow a file to be opened
 * for writing by only one {@code FileOutputStream} (or other
 * file-writing object) at a time.  In such situations the constructors in
 * this class will fail if the file involved is already open.
 *
 * <p>{@code FileOutputStream} is meant for writing streams of raw bytes
 * such as image data. For writing streams of characters, consider using
 * {@code FileWriter}.
 *
 * @apiNote
 * The {@link #close} method should be called to release resources used by this
 * stream, either directly, or with the {@code try}-with-resources statement.
 *
 * @implSpec
 * Subclasses are responsible for the cleanup of resources acquired by the subclass.
 * Subclasses requiring that resource cleanup take place after a stream becomes
 * unreachable should use {@link java.lang.ref.Cleaner} or some other mechanism.
 *
 * @author  Arthur van Hoff
 * @see     java.io.File
 * @see     java.io.FileDescriptor
 * @see     java.io.FileInputStream
 * @see     java.nio.file.Files#newOutputStream
 * @since   1.0
 */
public class FileOutputStream extends OutputStream
{
    /**
     * Access to FileDescriptor internals.
     */
    private static final JavaIOFileDescriptorAccess FD_ACCESS =
        SharedSecrets.getJavaIOFileDescriptorAccess();

    /**
     * Flag set by jdk.internal.event.JFRTracing to indicate if
     * file writes should be traced by JFR.
     */
    private static boolean jfrTracing;

    /**
     * The system dependent file descriptor.
     */
    private final FileDescriptor fd;

    /**
     * The associated channel, initialized lazily.
     */
    private volatile FileChannel channel;

    /**
     * The path of the referenced file
     * (null if the stream is created with a file descriptor)
     */
    private final String path;

    private final Object closeLock = new Object();

    private volatile boolean closed;

    private final boolean useNio;

    @SuppressWarnings({
            "FieldCanBeLocal",
            "this-escape",  // It immediately converts into a phantom reference.
    })
    private final NioChannelCleanable channelCleanable = new NioChannelCleanable(this);

    private final ExternalChannelHolder externalChannelHolder;

    /**
     * Creates a file output stream to write to the file with the
     * specified name. If the file exists, it is truncated, otherwise a
     * new file is created. {@linkplain java.nio.file##links Symbolic links}
     * are automatically redirected to the <i>target</i> of the link.
     * A new {@code FileDescriptor} object is
     * created to represent this file connection.
     * <p>
     * If the file exists but is a directory rather than a regular file, does
     * not exist but cannot be created, or cannot be opened for any other
     * reason then a {@code FileNotFoundException} is thrown.
     *
     * @implSpec Invoking this constructor with the parameter {@code name} is
     * equivalent to invoking {@link #FileOutputStream(String,boolean)
     * new FileOutputStream(name, false)}.
     *
     * @param      name   the system-dependent filename
     * @throws     FileNotFoundException  if the file exists but is a directory
     *                   rather than a regular file, does not exist but cannot
     *                   be created, or cannot be opened for any other reason
     */
    public FileOutputStream(String name) throws FileNotFoundException {
        this(name != null ? new File(name) : null, false);
    }

    /**
     * Creates a file output stream to write to the file with the specified
     * name. If the file exists, it is truncated unless the second
     * argument is {@code true}, in which case bytes will be written to the
     * end of the file rather than the beginning. If the file does not exist,
     * it is created. {@linkplain java.nio.file##links Symbolic links}
     * are automatically redirected to the <i>target</i> of the link.
     * A new {@code FileDescriptor} object is created to represent this
     * file connection.
     * <p>
     * If the file exists but is a directory rather than a regular file, does
     * not exist but cannot be created, or cannot be opened for any other
     * reason then a {@code FileNotFoundException} is thrown.
     *
     * @param     name        the system-dependent file name
     * @param     append      if {@code true}, then bytes will be written
     *                   to the end of the file rather than the beginning
     * @throws     FileNotFoundException  if the file exists but is a directory
     *                   rather than a regular file, does not exist but cannot
     *                   be created, or cannot be opened for any other reason.
     * @since     1.1
     */
    public FileOutputStream(String name, boolean append)
        throws FileNotFoundException
    {
        this(name != null ? new File(name) : null, append);
    }

    /**
     * Creates a file output stream to write to the file represented by
     * the specified {@code File} object.
     * If the file exists, it is truncated, otherwise a
     * new file is created. {@linkplain java.nio.file##links Symbolic links}
     * are automatically redirected to the <i>target</i> of the link.
     * A new {@code FileDescriptor} object is
     * created to represent this file connection.
     * <p>
     * If the file exists but is a directory rather than a regular file, does
     * not exist but cannot be created, or cannot be opened for any other
     * reason then a {@code FileNotFoundException} is thrown.
     *
     * @param      file               the file to be opened for writing.
     * @throws     FileNotFoundException  if the file exists but is a directory
     *                   rather than a regular file, does not exist but cannot
     *                   be created, or cannot be opened for any other reason
     * @see        java.io.File#getPath()
     */
    public FileOutputStream(File file) throws FileNotFoundException {
        this(file, false);
    }

    /**
     * Creates a file output stream to write to the file represented by
     * the specified {@code File} object.
     * If the file exists, it is truncated unless the second
     * argument is {@code true}, in which case bytes will be written to the
     * end of the file rather than the beginning. If the file does not exist,
     * it is created. {@linkplain java.nio.file##links Symbolic links}
     * are automatically redirected to the <i>target</i> of the link.
     * A new {@code FileDescriptor} object is created to represent this
     * file connection.
     * <p>
     * If the file exists but is a directory rather than a regular file, does
     * not exist but cannot be created, or cannot be opened for any other
     * reason then a {@code FileNotFoundException} is thrown.
     *
     * @param      file               the file to be opened for writing.
     * @param     append      if {@code true}, then bytes will be written
     *                   to the end of the file rather than the beginning
     * @throws     FileNotFoundException  if the file exists but is a directory
     *                   rather than a regular file, does not exist but cannot
     *                   be created, or cannot be opened for any other reason
     * @see        java.io.File#getPath()
     * @since 1.4
     */
    @SuppressWarnings("this-escape")
    public FileOutputStream(File file, boolean append)
        throws FileNotFoundException
    {
        if (file.isInvalid()) {
            throw new FileNotFoundException("Invalid file path");
        }
        this.path = file.getPath();

        java.nio.file.FileSystem nioFs = IoOverNioFileSystem.acquireNioFs(path);
        useNio = path != null && nioFs != null;
        if (useNio) {
            Path nioPath = nioFs.getPath(path);

            // java.io backend doesn't open DOS hidden files for writing, but java.nio.file opens.
            // This code mimics the old behavior.
            if (nioFs.getSeparator().equals("\\")) {
                DosFileAttributes attrs = null;
                try {
                    var view = Files.getFileAttributeView(nioPath, DosFileAttributeView.class);
                    if (view != null) {
                        attrs = view.readAttributes();
                    }
                } catch (IOException | UnsupportedOperationException _) {
                    // Windows paths without DOS attributes? Not a problem in this case.
                }
                if (attrs != null && (attrs.isHidden() || attrs.isDirectory())) {
                    throw new FileNotFoundException(file.getPath() + " (Access is denied)");
                }
            }

            Set<OpenOption> options = append
                    ? Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    : Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            var bundle = IoOverNioFileSystem.initializeStreamUsingNio(
                    this, nioFs, file, nioPath, options, channelCleanable);
            channel = bundle.channel();
            fd = bundle.fd();
            externalChannelHolder = bundle.externalChannelHolder();
        } else {
            this.fd = new FileDescriptor();
            fd.attach(this);

            open(this.path, append);
            FileCleanable.register(fd);   // open sets the fd, register the cleanup
            externalChannelHolder = null;
        }
        if (DEBUG.writeTraces()) {
            System.err.printf("Created a FileOutputStream for %s%n", file);
        }
    }

    /**
     * Creates a file output stream to write to the specified file
     * descriptor, which represents an existing connection to an actual
     * file in the file system.
     * <p>
     * If {@code fdObj} is null then a {@code NullPointerException}
     * is thrown.
     * <p>
     * This constructor does not throw an exception if {@code fdObj}
     * is {@link java.io.FileDescriptor#valid() invalid}.
     * However, if the methods are invoked on the resulting stream to attempt
     * I/O on the stream, an {@code IOException} is thrown.
     *
     * @param      fdObj   the file descriptor to be opened for writing
     */
    @SuppressWarnings("this-escape")
    public FileOutputStream(FileDescriptor fdObj) {
        useNio = false;
        externalChannelHolder = null;

        if (fdObj == null) {
            throw new NullPointerException();
        }
        this.fd = fdObj;
        this.path = null;

        fd.attach(this);
    }

    /**
     * Opens a file, with the specified name, for overwriting or appending.
     * @param name name of file to be opened
     * @param append whether the file is to be opened in append mode
     */
    private native void open0(String name, boolean append)
        throws FileNotFoundException;

    // wrap native call to allow instrumentation
    /**
     * Opens a file, with the specified name, for overwriting or appending.
     * @param name name of file to be opened
     * @param append whether the file is to be opened in append mode
     */
    private void open(String name, boolean append) throws FileNotFoundException {
        open0(name, append);
    }

    /**
     * Writes the specified byte to this file output stream.
     *
     * @param   b   the byte to be written.
     * @param   append   {@code true} if the write operation first
     *     advances the position to the end of file
     */
    private native void write(int b, boolean append) throws IOException;

    private void traceWrite(int b, boolean append) throws IOException {
        long bytesWritten = 0;
        long start = FileWriteEvent.timestamp();
        try {
            start = FileWriteEvent.timestamp();
            implWrite(b, append);
            bytesWritten = 1;
        } finally {
            FileWriteEvent.offer(start, path, bytesWritten);
        }
    }

    /**
     * Writes the specified byte to this file output stream. Implements
     * the {@code write} method of {@code OutputStream}.
     *
     * @param      b   the byte to be written.
     * @throws     IOException  if an I/O error occurs.
     */
    @Override
    public void write(int b) throws IOException {
        boolean append = FD_ACCESS.getAppend(fd);
        if (jfrTracing && FileWriteEvent.enabled() && !IoOverNio.isAllowedInThisThread()) {
            traceWrite(b, append);
            return;
        }
        implWrite(b, append);
    }

    private void implWrite(int b, boolean append) throws IOException {
        if (!useNio) {
            write(b, append);
        } else {
            // 'append' is ignored; the channel is supposed to obey the mode in which the file was opened
            byte[] array = new byte[1];
            array[0] = (byte) b;
            ByteBuffer buffer = ByteBuffer.wrap(array);
            do {
                channel.write(buffer);
            } while (buffer.hasRemaining());
        }
    }

    /**
     * Writes a sub array as a sequence of bytes.
     * @param b the data to be written
     * @param off the start offset in the data
     * @param len the number of bytes that are written
     * @param append {@code true} to first advance the position to the
     *     end of file
     * @throws    IOException If an I/O error has occurred.
     */
    private native void writeBytes(byte[] b, int off, int len, boolean append)
        throws IOException;

    private void traceWriteBytes(byte b[], int off, int len, boolean append) throws IOException {
        long bytesWritten = 0;
        long start = FileWriteEvent.timestamp();
        try {
            start = FileWriteEvent.timestamp();
            implWriteBytes(b, off, len, append);
            bytesWritten = len;
        } finally {
            FileWriteEvent.offer(start, path, bytesWritten);
        }
    }

    /**
     * Writes {@code b.length} bytes from the specified byte array
     * to this file output stream.
     *
     * @param      b   {@inheritDoc}
     * @throws     IOException  {@inheritDoc}
     */
    @Override
    public void write(byte[] b) throws IOException {
        boolean append = FD_ACCESS.getAppend(fd);
        if (jfrTracing && FileWriteEvent.enabled() && !IoOverNio.isAllowedInThisThread()) {
            traceWriteBytes(b, 0, b.length, append);
            return;
        }

        implWriteBytes(b, 0, b.length, append);
    }

    /**
     * Writes {@code len} bytes from the specified byte array
     * starting at offset {@code off} to this file output stream.
     *
     * @param      b     {@inheritDoc}
     * @param      off   {@inheritDoc}
     * @param      len   {@inheritDoc}
     * @throws     IOException  if an I/O error occurs.
     * @throws     IndexOutOfBoundsException {@inheritDoc}
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        boolean append = FD_ACCESS.getAppend(fd);
        if (jfrTracing && FileWriteEvent.enabled() && !IoOverNio.isAllowedInThisThread()) {
            traceWriteBytes(b, off, len, append);
            return;
        }
        implWriteBytes(b, off, len, append);
    }

    private void implWriteBytes(byte[] b, int off, int len, boolean append) throws IOException {
        if (!useNio) {
            writeBytes(b, off, len, append);
        } else {
            // 'append' is ignored; the channel is supposed to obey the mode in which the file was opened
            try {
                ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
                do {
                    channel.write(buffer);
                } while (buffer.hasRemaining());
            } catch (OutOfMemoryError e) {
                // May fail to allocate direct buffer memory due to small -XX:MaxDirectMemorySize
                writeBytes(b, off, len, append);
            }
        }
    }

    /**
     * Closes this file output stream and releases any system resources
     * associated with this stream. This file output stream may no longer
     * be used for writing bytes.
     *
     * <p> If this stream has an associated channel then the channel is closed
     * as well.
     *
     * @apiNote
     * Overriding {@link #close} to perform cleanup actions is reliable
     * only when called directly or when called by try-with-resources.
     *
     * @implSpec
     * Subclasses requiring that resource cleanup take place after a stream becomes
     * unreachable should use the {@link java.lang.ref.Cleaner} mechanism.
     *
     * <p>
     * If this stream has an associated channel then this method will close the
     * channel, which in turn will close this stream. Subclasses that override
     * this method should be prepared to handle possible reentrant invocation.
     *
     * @throws     IOException  if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        FileChannel fc = channel;
        if (fc != null) {
            // possible race with getChannel(), benign since
            // FileChannel.close is final and idempotent
            fc.close();
        }

        if (externalChannelHolder != null) {
            externalChannelHolder.close();
        }

        fd.closeAll(new Closeable() {
            public void close() throws IOException {
               fd.close();
           }
        });
    }

    /**
     * Returns the file descriptor associated with this stream.
     *
     * @return  the {@code FileDescriptor} object that represents
     *          the connection to the file in the file system being used
     *          by this {@code FileOutputStream} object.
     *
     * @throws     IOException  if an I/O error occurs.
     * @see        java.io.FileDescriptor
     */
     public final FileDescriptor getFD()  throws IOException {
        if (fd != null) {
            return fd;
        }
        throw new IOException();
     }

    /**
     * Returns the unique {@link java.nio.channels.FileChannel FileChannel}
     * object associated with this file output stream.
     *
     * <p> The initial {@link java.nio.channels.FileChannel#position()
     * position} of the returned channel will be equal to the
     * number of bytes written to the file so far unless this stream is in
     * append mode, in which case it will be equal to the size of the file.
     * Writing bytes to this stream will increment the channel's position
     * accordingly.  Changing the channel's position, either explicitly or by
     * writing, will change this stream's file position.
     *
     * @return  the file channel associated with this file output stream
     *
     * @since 1.4
     */
    public FileChannel getChannel() {
        if (externalChannelHolder != null) {
            return externalChannelHolder.getInterruptibleChannel();
        }

        FileChannel fc = this.channel;
        if (fc == null) {
            synchronized (this) {
                fc = this.channel;
                if (fc == null) {
                    fc = FileChannelImpl.open(fd, path, false, true, false, false, this);
                    this.channel = fc;
                    if (closed) {
                        try {
                            // possible race with close(), benign since
                            // FileChannel.close is final and idempotent
                            fc.close();
                        } catch (IOException ioe) {
                            throw new InternalError(ioe); // should not happen
                        }
                    }
                }
            }
        }
        return fc;
    }

    private static native void initIDs();

    static {
        initIDs();
    }
}
