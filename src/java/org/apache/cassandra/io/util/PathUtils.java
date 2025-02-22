/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.util;

import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.io.FSError;
import org.apache.cassandra.io.FSReadError;
import org.apache.cassandra.io.FSWriteError;
import org.apache.cassandra.utils.NoSpamLogger;

import static java.nio.file.StandardOpenOption.*;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Collections.unmodifiableSet;
import static org.apache.cassandra.utils.Throwables.merge;

/**
 * Vernacular: tryX means return false or 0L on any failure; XIfNotY means propagate any exceptions besides those caused by Y
 *
 * This class tries to apply uniform IOException handling, and does not propagate IOException except for NoSuchFileException.
 * Any harmless/application error exceptions are propagated as UncheckedIOException, and anything else as an FSReadError or FSWriteError.
 * Semantically this is a little incoherent throughout the codebase, as we intercept IOException haphazardly and treat
 * it inconsistently - we should ideally migrate to using {@link #propagate(IOException, Path, boolean)} et al globally.
 */
public final class PathUtils
{
    private static final boolean consistentDirectoryListings = CassandraRelevantProperties.CONSISTENT_DIRECTORY_LISTINGS.getBoolean();

    private static final Set<StandardOpenOption> READ_OPTIONS = unmodifiableSet(EnumSet.of(READ));
    private static final Set<StandardOpenOption> WRITE_OPTIONS = unmodifiableSet(EnumSet.of(WRITE, CREATE, TRUNCATE_EXISTING));
    private static final Set<StandardOpenOption> WRITE_APPEND_OPTIONS = unmodifiableSet(EnumSet.of(WRITE, CREATE, APPEND));
    private static final Set<StandardOpenOption> READ_WRITE_OPTIONS = unmodifiableSet(EnumSet.of(READ, WRITE, CREATE));
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute[0];

    private static final Logger logger = LoggerFactory.getLogger(PathUtils.class);
    private static final NoSpamLogger nospam1m = NoSpamLogger.getLogger(logger, 1, TimeUnit.MINUTES);

    private static final boolean USE_NIX_RECURSIVE_DELETE = CassandraRelevantProperties.USE_NIX_RECURSIVE_DELETE.getBoolean();

    private static volatile boolean DAEMON_SETUP_COMPLETED = false;

    private static Consumer<Path> onDeletion = path -> {
        if (DAEMON_SETUP_COMPLETED)
            setDeletionListener(ignore -> {});
        else if (logger.isTraceEnabled())
            logger.trace("Deleting file during startup: {}", path);
    };

    public static void daemonSetupCompleted()
    {
        DAEMON_SETUP_COMPLETED = true;
    }

    public static FileChannel newReadChannel(Path path) throws NoSuchFileException
    {
        return newFileChannel(path, READ_OPTIONS);
    }

    public static FileChannel newReadWriteChannel(Path path) throws NoSuchFileException
    {
        return newFileChannel(path, READ_WRITE_OPTIONS);
    }

    public static FileChannel newWriteOverwriteChannel(Path path) throws NoSuchFileException
    {
        return newFileChannel(path, WRITE_OPTIONS);
    }

    public static FileChannel newWriteAppendChannel(Path path) throws NoSuchFileException
    {
        return newFileChannel(path, WRITE_APPEND_OPTIONS);
    }

    private static FileChannel newFileChannel(Path path, Set<StandardOpenOption> options) throws NoSuchFileException
    {
        try
        {
            return FileChannel.open(path, options, PathUtils.NO_ATTRIBUTES);
        }
        catch (IOException e)
        {
            throw propagateUncheckedOrNoSuchFileException(e, path, options.contains(WRITE));
        }
    }

    public static void setDeletionListener(Consumer<Path> newOnDeletion)
    {
        onDeletion = newOnDeletion;
    }

    public static String filename(Path path)
    {
        return path.getFileName().toString();
    }

    public static <T> T[] list(Path path, Function<Stream<Path>, Stream<T>> transform, IntFunction<T[]> arrayFactory)
    {
        try (Stream<Path> stream = Files.list(path))
        {
            return transform.apply(consistentDirectoryListings ? stream.sorted() : stream)
                    .toArray(arrayFactory);
        }
        catch (NoSuchFileException e)
        {
            return null;
        }
        catch (IOException e)
        {
            throw propagateUnchecked(e, path, false);
        }
    }

    public static <T> T[] tryList(Path path, Function<Stream<Path>, Stream<T>> transform, IntFunction<T[]> arrayFactory)
    {
        try (Stream<Path> stream = Files.list(path))
        {
            return transform.apply(consistentDirectoryListings ? stream.sorted() : stream)
                    .toArray(arrayFactory);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    public static void forEach(Path path, Consumer<Path> pathConsumer)
    {
        try (Stream<Path> stream = Files.list(path))
        {
            (consistentDirectoryListings ? stream.sorted() : stream).forEach(pathConsumer);
        }
        catch (IOException e)
        {
            throw propagateUnchecked(e, path, false);
        }
    }

    public static void forEachRecursive(Path path, Consumer<Path> pathConsumer)
    {
        Consumer<Path> recursivePathConsumer = new Consumer<Path>()
        {
            @Override
            public void accept(Path childPath)
            {
                pathConsumer.accept(childPath);
                if (isDirectory(childPath))
                    forEach(childPath, this);
            }
        };
        forEach(path, recursivePathConsumer);
    }

    public static long tryGetLength(Path path)
    {
        return tryOnPath(path, Files::size);
    }

    public static long tryGetLastModified(Path path)
    {
        return tryOnPath(path, p -> Files.getLastModifiedTime(p).toMillis());
    }

    public static boolean trySetLastModified(Path path, long lastModified)
    {
        try
        {
            Files.setLastModifiedTime(path, FileTime.fromMillis(lastModified));
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    public static boolean trySetReadable(Path path, boolean readable)
    {
        return trySet(path, PosixFilePermission.OWNER_READ, readable);
    }

    public static boolean trySetWritable(Path path, boolean writeable)
    {
        return trySet(path, PosixFilePermission.OWNER_WRITE, writeable);
    }

    public static boolean trySetExecutable(Path path, boolean executable)
    {
        return trySet(path, PosixFilePermission.OWNER_EXECUTE, executable);
    }

    public static boolean trySet(Path path, PosixFilePermission permission, boolean set)
    {
        try
        {
            PosixFileAttributeView view = path.getFileSystem().provider().getFileAttributeView(path, PosixFileAttributeView.class);
            PosixFileAttributes attributes = view.readAttributes();
            Set<PosixFilePermission> permissions = attributes.permissions();
            if (set == permissions.contains(permission))
                return true;
            if (set) permissions.add(permission);
            else permissions.remove(permission);
            view.setPermissions(permissions);
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    public static Throwable delete(Path file, Throwable accumulate)
    {
        try
        {
            delete(file);
        }
        catch (FSError t)
        {
            accumulate = merge(accumulate, t);
        }
        return accumulate;
    }

    public static void delete(Path file)
    {
        try
        {
            Files.delete(file);
            onDeletion.accept(file);
        }
        catch (IOException e)
        {
            throw propagateUnchecked(e, file, true);
        }
    }

    public static boolean tryDelete(Path file)
    {
        try
        {
            Files.delete(file);
            onDeletion.accept(file);
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    public static void delete(Path file, @Nullable RateLimiter rateLimiter)
    {
        if (rateLimiter != null)
        {
            double throttled = rateLimiter.acquire();
            if (throttled > 0.0)
                nospam1m.warn("Throttling file deletion: waited {} seconds to delete {}", throttled, file);
        }
        delete(file);
    }

    public static Throwable delete(Path file, Throwable accumulate, @Nullable RateLimiter rateLimiter)
    {
        try
        {
            delete(file, rateLimiter);
        }
        catch (Throwable t)
        {
            accumulate = merge(accumulate, t);
        }
        return accumulate;
    }

    private static void deleteRecursiveUsingNixCommand(Path path, boolean quietly)
    {
        try
        {
            String[] cmd = new String[]{ "rm", quietly ? "-rf" : "-r", path.toAbsolutePath().toString() };
            int result = Runtime.getRuntime().exec(cmd).waitFor();
            if (result != 0)
                throw new IOException(String.format("'%s' returned non-zero exit code: %d", Arrays.toString(cmd), result));
            onDeletion.accept(path);
        }
        catch (IOException e)
        {
            throw propagateUnchecked(e, path, true);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new FSWriteError(e, path);
        }
    }

    /**
     * Deletes all files and subdirectories under "path".
     * @param path file to be deleted
     * @throws FSWriteError if any part of the tree cannot be deleted
     */
    public static void deleteRecursive(Path path)
    {
        if (USE_NIX_RECURSIVE_DELETE && path.getFileSystem() == FileSystems.getDefault())
        {
            deleteRecursiveUsingNixCommand(path, false);
            return;
        }

        if (isDirectory(path))
            forEach(path, PathUtils::deleteRecursive);

        // The directory is now empty so now it can be smoked
        delete(path);
    }

    /**
     * Deletes all files and subdirectories under "path",
     * ignoring IOExceptions along the way.
     * @param path file to be deleted
     */
    public static void deleteQuietly(Path path)
    {
        if (USE_NIX_RECURSIVE_DELETE && path.getFileSystem() == FileSystems.getDefault())
        {
            deleteRecursiveUsingNixCommand(path, true);
            return;
        }

        if (isDirectory(path))
            forEach(path, PathUtils::deleteQuietly);

        // The directory is now empty so now it can be smoked
        tryDelete(path);
    }

    /**
     * Deletes all files and subdirectories under "path".
     * @param path file to be deleted
     * @throws FSWriteError if any part of the tree cannot be deleted
     */
    public static void deleteRecursive(Path path, RateLimiter rateLimiter)
    {
        deleteRecursive(path, rateLimiter, p -> deleteRecursive(p, rateLimiter));
    }

    /**
     * Deletes all files and subdirectories under "path".
     * @param path file to be deleted
     * @throws FSWriteError if any part of the tree cannot be deleted
     */
    private static void deleteRecursive(Path path, RateLimiter rateLimiter, Consumer<Path> deleteRecursive)
    {
        if (isDirectory(path))
            forEach(path, deleteRecursive);

        // The directory is now empty so now it can be smoked
        delete(path, rateLimiter);
    }

    /**
     * Recursively delete the content of the directory, but not the directory itself.
     * @param dirPath directory for which content should be deleted 
     */
    public static void deleteContent(Path dirPath)
    {
        if (isDirectory(dirPath))
            forEach(dirPath, PathUtils::deleteRecursive);
    }

    /**
     * List all paths in this directory
     * @param dirPath directory for which to list all paths
     * @return list of all paths contained in the given directory
     */
    public static List<Path> listPaths(Path dirPath)
    {
        return listPaths(dirPath, p -> true);
    }

    /**
     * List paths in this directory that match the filter
     * @param dirPath directory for which to list all paths matching the given filter
     * @param filter predicate used to filter paths
     * @return filtered list of paths contained in the given directory
     */
    public static List<Path> listPaths(Path dirPath, Predicate<Path> filter)
    {
        try (Stream<Path> stream = Files.list(dirPath))
        {
            return (consistentDirectoryListings ? stream.sorted() : stream).filter(filter).collect(Collectors.toList());
        }
        catch(NotDirectoryException | NoSuchFileException ex)
        {
            // Don't throw if the file does not exist or is not a directory
            return ImmutableList.of();
        }
        catch(IOException ex)
        {
            throw new FSReadError(ex, dirPath);
        }
    }

    /**
     * Schedules deletion of all file and subdirectories under "dir" on JVM shutdown.
     * @param dir Directory to be deleted
     */
    public synchronized static void deleteRecursiveOnExit(Path dir)
    {
        ON_EXIT.add(dir, true);
    }

    /**
     * Schedules deletion of the file only on JVM shutdown.
     * @param file File to be deleted
     */
    public synchronized static void deleteOnExit(Path file)
    {
        ON_EXIT.add(file, false);
    }

    public static boolean tryRename(Path from, Path to)
    {
        logger.trace("Renaming {} to {}", from, to);
        // this is not FSWE because usually when we see it it's because we didn't close the file before renaming it,
        // and Windows is picky about that.
        try
        {
            atomicMoveWithFallback(from, to);
            return true;
        }
        catch (IOException e)
        {
            logger.trace("Could not move file {} to {}", from, to, e);
            return false;
        }
    }

    public static void rename(Path from, Path to)
    {
        logger.trace("Renaming {} to {}", from, to);
        // this is not FSWE because usually when we see it it's because we didn't close the file before renaming it,
        // and Windows is picky about that.
        try
        {
            atomicMoveWithFallback(from, to);
        }
        catch (IOException e)
        {
            logger.trace("Could not move file {} to {}", from, to, e);

            // TODO: this should be an FSError (either read or write)?
            // (but for now this is maintaining legacy semantics)
            throw new RuntimeException(String.format("Failed to rename %s to %s", from, to), e);
        }
    }

    /**
     * Move a file atomically, if it fails, it falls back to a non-atomic operation
     */
    private static void atomicMoveWithFallback(Path from, Path to) throws IOException
    {
        try
        {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            logger.trace("Could not do an atomic move", e);
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Copy a file to a target file
     */
    public static void copy(Path from, Path to, StandardCopyOption option)
    {
        logger.trace("Copying {} to {}", from, to);
        try
        {
            Files.copy(from, to, option);
        }
        catch (IOException e)
        {
            throw new RuntimeException(String.format("Failed to copy %s to %s", from, to), e);
        }
    }

    // true if can determine exists, false if any exception occurs
    public static boolean exists(Path path)
    {
        return Files.exists(path);
    }

    // true if can determine is a directory, false if any exception occurs
    public static boolean isDirectory(Path path)
    {
        return Files.isDirectory(path);
    }

    // true if can determine is a regular file, false if any exception occurs
    public static boolean isFile(Path path)
    {
        return Files.isRegularFile(path);
    }

    /**
     * @param path create file if not exists
     * @throws IOError if cannot perform the operation
     * @return true if a new file was created
     */
    public static boolean createFileIfNotExists(Path path)
    {
        return ifNotExists(path, Files::createFile);
    }

    /**
     * @param path create directory if not exists
     * @throws IOError if cannot perform the operation
     * @return true if a new directory was created
     */
    public static boolean createDirectoryIfNotExists(Path path)
    {
        return ifNotExists(path, Files::createDirectory);
    }

    /**
     * @param path create directory (and parents) if not exists
     * @throws IOError if cannot perform the operation
     * @return true if a new directory was created
     */
    public static boolean createDirectoriesIfNotExists(Path path)
    {
        return ifNotExists(path, Files::createDirectories);
    }

    /**
     * @param path create directory if not exists and action can be performed
     * @return true if a new directory was created, false otherwise (for any reason)
     */
    public static boolean tryCreateDirectory(Path path)
    {
        return tryConsume(path, Files::createDirectory);
    }

    /**
     * @param path create directory (and parents) if not exists and action can be performed
     * @return true if the new directory was created, false otherwise (for any reason)
     */
    public static boolean tryCreateDirectories(Path path)
    {
        if (exists(path))
            return false;

        tryCreateDirectories(path.toAbsolutePath().getParent());
        return tryCreateDirectory(path);
    }

    /**
     * @return file if exists, otherwise nearest parent that exists; null if nothing in path exists
     */
    public static Path findExistingAncestor(Path file)
    {
        if (!file.equals(file.normalize()))
            throw new IllegalArgumentException("Must be invoked on a path without redundant elements");

        Path parent = file;
        boolean isRelative = !file.isAbsolute();
        while (parent != null && !Files.exists(parent))
        {
            parent = parent.getParent();
            if (parent == null && isRelative)
                parent = Paths.get("");
        }
        return parent;
    }

    /**
     * 1) Convert to an absolute path without redundant path elements;
     * 2) If the file exists, resolve any links to the underlying fille;
     * 3) If the file does not exist, find the first ancestor that does and resolve the path from there
     */
    public static Path toCanonicalPath(Path file)
    {
        Preconditions.checkNotNull(file);

        file = file.toAbsolutePath().normalize();
        Path parent = findExistingAncestor(file);

        if (parent == null)
            return file;
        if (parent == file)
            return toRealPath(file);
        return toRealPath(parent).resolve(parent.relativize(file));
    }

    /**
     * @param path to check file szie
     * @return file size or 0 if failed to get file size
     */
    public static long size(Path path)
    {
        try
        {
            return Files.size(path);
        }
        catch (IOException e)
        {
            // it's possible that between the time that the caller has checked if the file exists and the time it retrieves the creation time,
            // the file is actually deleted. File.length() returns a positive value only if the file is valid, otherwise it returns 0L, here
            // we do the same
            return 0;
        }
    }

    /**
     * @param pathOrURI path or uri in string
     * @return nio Path
     */
    public static Path getPath(String pathOrURI)
    {
        try
        {
            return Paths.get(URI.create(pathOrURI));
        }
        catch (IllegalArgumentException ex)
        {
            return Paths.get(pathOrURI);
        }
    }

    private static Path toRealPath(Path path)
    {
        try
        {
            return path.toRealPath();
        }
        catch (IOException e)
        {
            throw propagateUnchecked(e, path, false);
        }
    }

    /**
     * Return true if file's canonical path is contained in folder's canonical path.
     *
     * Propagates any exceptions encountered finding canonical paths.
     */
    public static boolean isContained(Path folder, Path file)
    {
        Path realFolder = toCanonicalPath(folder), realFile = toCanonicalPath(file);
        return realFile.startsWith(realFolder);
    }

    private static final class DeleteOnExit implements Runnable
    {
        private boolean isRegistered;
        private final Set<Path> deleteRecursivelyOnExit = new HashSet<>();
        private final Set<Path> deleteOnExit = new HashSet<>();

        DeleteOnExit()
        {
            Runtime.getRuntime().addShutdownHook(new Thread(this));
        }

        synchronized void add(Path path, boolean recursive)
        {
            if (!isRegistered)
            {
                isRegistered = true;
            }
            logger.trace("Scheduling deferred {}deletion of file: {}", recursive ? "recursive " : "", path);
            (recursive ? deleteRecursivelyOnExit : deleteOnExit).add(path);
        }

        public void run()
        {
            for (Path path : deleteOnExit)
            {
                try
                {
                    if (exists(path))
                        delete(path);
                }
                catch (Throwable t)
                {
                    logger.warn("Failed to delete {} on exit", path, t);
                }
            }
            for (Path path : deleteRecursivelyOnExit)
            {
                try
                {
                    if (exists(path))
                        deleteRecursive(path);
                }
                catch (Throwable t)
                {
                    logger.warn("Failed to delete {} on exit", path, t);
                }
            }
        }
    }
    private static final DeleteOnExit ON_EXIT = new DeleteOnExit();

    public interface IOConsumer { void accept(Path path) throws IOException; }
    public interface IOToLongFunction<V> { long apply(V path) throws IOException; }

    private static boolean ifNotExists(Path path, IOConsumer consumer)
    {
        try
        {
            consumer.accept(path);
            return true;
        }
        catch (FileAlreadyExistsException fae)
        {
            return false;
        }
        catch (IOException e)
        {
            throw propagateUnchecked(e, path, true);
        }
    }

    private static boolean tryConsume(Path path, IOConsumer function)
    {
        try
        {
            function.accept(path);
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private static long tryOnPath(Path path, IOToLongFunction<Path> function)
    {
        try
        {
            return function.apply(path);
        }
        catch (IOException e)
        {
            return 0L;
        }
    }

    private static long tryOnFileStore(Path path, IOToLongFunction<FileStore> function)
    {
        return tryOnFileStore(path, function, ignore -> {});
    }

    private static long tryOnFileStore(Path path, IOToLongFunction<FileStore> function, Consumer<IOException> orElse)
    {
        try
        {
            Path ancestor = findExistingAncestor(path.normalize().toAbsolutePath());
            if (ancestor == null)
            {
                orElse.accept(new NoSuchFileException(path.toString()));
                return 0L;
            }
            return function.apply(Files.getFileStore(ancestor));
        }
        catch (IOException e)
        {
            orElse.accept(e);
            return 0L;
        }
    }

    /**
     * Returns the number of bytes (determined by the provided MethodHandle) on the specified partition.
     * <p>This method handles large file system by returning {@code Long.MAX_VALUE} if the  number of available bytes
     * overflow. See <a href='https://bugs.openjdk.java.net/browse/JDK-8179320'>JDK-8179320</a> for more information</p>
     *
     * @param path the partition (or a file within it)
     */
    public static long tryGetSpace(Path path, IOToLongFunction<FileStore> getSpace)
    {
        return handleLargeFileSystem(tryOnFileStore(path, getSpace));
    }

    public static long tryGetSpace(Path path, IOToLongFunction<FileStore> getSpace, Consumer<IOException> orElse)
    {
        return handleLargeFileSystem(tryOnFileStore(path, getSpace, orElse));
    }

    /**
     * Handle large file system by returning {@code Long.MAX_VALUE} when the size overflows.
     * @param size returned by the Java's FileStore methods
     * @return the size or {@code Long.MAX_VALUE} if the size was bigger than {@code Long.MAX_VALUE}
     */
    public static long handleLargeFileSystem(long size)
    {
        return size < 0 ? Long.MAX_VALUE : size;
    }

    /**
     * Private constructor as the class contains only static methods.
     */
    private PathUtils()
    {
    }

    /**
     * propagate an IOException as an FSWriteError, FSReadError or UncheckedIOException
     */
    public static RuntimeException propagateUnchecked(IOException ioe, Path path, boolean write)
    {
        if (ioe instanceof FileAlreadyExistsException
            || ioe instanceof NoSuchFileException
            || ioe instanceof AtomicMoveNotSupportedException
            || ioe instanceof java.nio.file.DirectoryNotEmptyException
            || ioe instanceof java.nio.file.FileSystemLoopException
            || ioe instanceof java.nio.file.NotDirectoryException
            || ioe instanceof java.nio.file.NotLinkException)
            throw new UncheckedIOException(ioe);

        if (write) throw new FSWriteError(ioe, path);
        else throw new FSReadError(ioe, path);
    }

    /**
     * propagate an IOException as an FSWriteError, FSReadError or UncheckedIOException - except for NoSuchFileException
     */
    public static NoSuchFileException propagateUncheckedOrNoSuchFileException(IOException ioe, Path path, boolean write) throws NoSuchFileException
    {
        if (ioe instanceof NoSuchFileException)
            throw (NoSuchFileException) ioe;

        throw propagateUnchecked(ioe, path, write);
    }

    /**
     * propagate an IOException either as itself or an FSWriteError or FSReadError
     */
    public static <E extends IOException> E propagate(E ioe, Path path, boolean write) throws E
    {
        if (ioe instanceof FileAlreadyExistsException
            || ioe instanceof NoSuchFileException
            || ioe instanceof AtomicMoveNotSupportedException
            || ioe instanceof java.nio.file.DirectoryNotEmptyException
            || ioe instanceof java.nio.file.FileSystemLoopException
            || ioe instanceof java.nio.file.NotDirectoryException
            || ioe instanceof java.nio.file.NotLinkException)
            throw ioe;

        if (write) throw new FSWriteError(ioe, path);
        else throw new FSReadError(ioe, path);
    }
}
