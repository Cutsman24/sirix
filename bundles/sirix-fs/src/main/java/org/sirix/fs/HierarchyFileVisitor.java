package org.sirix.fs;

import static com.google.common.base.Preconditions.checkNotNull;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import org.brackit.xquery.atomic.QNm;
import org.sirix.api.Database;
import org.sirix.api.XdmNodeReadTrx;
import org.sirix.api.XdmNodeWriteTrx;
import org.sirix.exception.SirixException;
import org.sirix.service.xml.shredder.AbstractShredder;
import org.sirix.service.xml.shredder.Insert;
import org.sirix.utils.LogWrapper;
import org.slf4j.LoggerFactory;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;

/**
 * Implements the {@link FileVisitor} interface and shredders an XML representation of
 * directories/files into sirix. The XML representation can be easily equipped with further
 * functionality through the usage of the "Execute Around" idiom.
 *
 * Further functionality can be plugged in through the implementation of the {@link Visitor}
 * interface.
 *
 * @author Johannes Lichtenberger, University of Konstanz
 *
 */
@Nonnull
public class HierarchyFileVisitor extends AbstractShredder
    implements AutoCloseable, FileVisitor<Path> {

  /**
   * Mapping of {@link Database} to {@link HierarchyFileVisitor} shared among all instances.
   */
  private static final ConcurrentMap<XdmNodeWriteTrx, HierarchyFileVisitor> INSTANCES =
      new ConcurrentHashMap<>();

  /** {@link LogWrapper} reference. */
  private static final LogWrapper LOGWRAPPER =
      new LogWrapper(LoggerFactory.getLogger(HierarchyFileVisitor.class));

  /** sirix {@link XdmNodeWriteTrx}. */
  private final XdmNodeWriteTrx mWtx;

  /**
   * Visitor which simply can be plugged in to create a more thorough XML representation.
   */
  private final Optional<Visitor<XdmNodeWriteTrx>> mVisitor;

  /** Index entries {@code String} representation to {@link Path} value. */
  private final Map<Path, org.sirix.fs.Path> mIndex;

  /** Simple Builder. */
  public static class Builder {

    /** sirix {@link XdmNodeWriteTrx}. */
    private final XdmNodeWriteTrx mWtx;

    /** Implementation of the {@link Visitor} interface. */
    private Optional<Visitor<XdmNodeWriteTrx>> mVisitor = Optional.absent();

    /**
     * Constructor.
     *
     * @param pDatabase sirix {@link XdmNodeWriteTrx}
     */
    public Builder(final XdmNodeWriteTrx pWtx) {
      mWtx = checkNotNull(pWtx);
    }

    /**
     * Set an {@link Visitor} implementation.
     *
     * @param pVisitor {@link Visitor} implementation
     * @return this builder instance
     */
    public Builder setVisitor(final Visitor<XdmNodeWriteTrx> pVisitor) {
      mVisitor = Optional.fromNullable(pVisitor);
      return this;
    }

    /**
     * Build a new {@link HierarchyFileVisitor} instance.
     *
     * @return {@link HierarchyFileVisitor} reference
     * @throws SirixException if setting up sirix fails
     */
    public HierarchyFileVisitor build() throws SirixException {
      return new HierarchyFileVisitor(this);
    }
  }

  /**
   * Private constructor invoked from factory.
   *
   * @param pPath {@link Path} reference which denotes the {@code path/directory} to watch for
   *        changes.
   * @param pDatabase {@link Database} to use for importing changed data into sirix
   * @throws NullPointerException if {@code pBuilder} is {@code null}
   */
  private HierarchyFileVisitor(final Builder builder) throws SirixException {
    super(builder.mWtx, Insert.ASFIRSTCHILD);
    mVisitor = builder.mVisitor;
    mWtx = builder.mWtx;
    mWtx.insertElementAsFirstChild(new QNm("fsml"));
    mIndex = Maps.newHashMap();
  }

  /**
   * Get an instance of {@link FileHierarchyWalker}. If an instance with the specified {@code {@link
   * Path}/ {@link Database} already exists this instance is returned.
   *
   * @param pPath {@link Path} reference which denotes the {@code path/directory} to watch for
   * changes.
   * 
   * @return a new {@link FileHierarchyWalker} instance
   * @throws NullPointerException if {@code pBuilder} is {@code null}
   * @throws SirixException if anything while setting up sirix failes
   */
  public static synchronized HierarchyFileVisitor getInstance(final Builder builder)
      throws SirixException {
    HierarchyFileVisitor visitor = INSTANCES.putIfAbsent(builder.mWtx, builder.build());
    if (visitor == null) {
      visitor = INSTANCES.get(builder.mWtx);
    }
    return visitor;
  }

  /**
   * <p>
   * Invoked for a directory before directory contents are visited.
   * </p>
   * <p>
   * Inserts a {@code dir-element} with a {@code name-attribute} for the directory to visit.
   * </p>
   * <p>
   * An optional visitor can be used to add further attributes or metadata. The sirix
   * {@link XdmNodeWriteTrx} is located on the new directory before and after using a pluggable
   * visitor.
   * </p>
   *
   * @throws NullPointerException if {@code pDir} or {@code pAttrs} is {@code null}
   */
  @Override
  public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs)
      throws IOException {
    checkNotNull(dir);
    checkNotNull(attrs);
    try {
      mIndex.put(dir, org.sirix.fs.Path.ISDIRECTORY);
      processStartTag(new QNm("dir"));
      mWtx.insertAttribute(new QNm("name"), dir.getFileName().toString());
      mWtx.moveToParent();
      final long nodeKey = mWtx.getNodeKey();
      processDirectory(mVisitor, mWtx, dir, attrs);
      mWtx.moveTo(nodeKey);
    } catch (SirixException e) {
      LOGWRAPPER.error(e.getMessage(), e);
    }
    return FileVisitResult.CONTINUE;
  }

  @Override
  public FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
      throws IOException {
    checkNotNull(dir);
    if (exc != null) {
      LOGWRAPPER.debug(exc.getMessage(), exc);
    }
    processEndTag(new QNm(""));
    return FileVisitResult.CONTINUE;
  }

  /**
   * <p>
   * Inserts a {@code file-element} with a {@code name-attribute} for the file which is visited.
   * </p>
   * <p>
   * An optional visitor can be used to add further attributes or metadata. The sirix
   * {@link XdmNodeWriteTrx} is located on the new directory before and after using a pluggable
   * visitor.
   * </p>
   *
   * @throws NullPointerException if {@code pDir} or {@code pAttrs} is {@code null}
   */
  @Override
  public FileVisitResult visitFile(final Path pFile, final BasicFileAttributes pAttrs)
      throws IOException {
    checkNotNull(pFile);
    checkNotNull(pAttrs);
    try {
      if (Files.isRegularFile(pFile) | Files.isSymbolicLink(pFile)) {
        mIndex.put(pFile, org.sirix.fs.Path.ISFILE);
        processEmptyElement(new QNm("file"));
        mWtx.insertAttribute(new QNm("name"), pFile.getFileName().toString());
        mWtx.moveToParent();
        final long nodeKey = mWtx.getNodeKey();
        processFile(mVisitor, mWtx, pFile, pAttrs);
        mWtx.moveTo(nodeKey);
      }
    } catch (SirixException e) {
      LOGWRAPPER.error(e.getMessage(), e);
    }
    return FileVisitResult.CONTINUE;
  }

  /**
   * Process a directory.
   *
   * @param pVisitor an optional visitor implementing {@link Visitor}
   * @param pWtx sirix {@link XdmNodeWriteTrx}
   * @see Visitor#processDirectory(XdmNodeReadTrx) processDirectory(IReadTransaction)
   */
  private void processDirectory(final Optional<Visitor<XdmNodeWriteTrx>> pVisitor,
      final XdmNodeWriteTrx pWtx, final Path pDir, final BasicFileAttributes pAttrs) {
    assert pVisitor != null;
    assert pWtx != null;
    if (pVisitor.isPresent()) {
      pVisitor.get().processDirectory(pWtx, pDir, Optional.of(pAttrs));
    }
  }

  /**
   * Process a file.
   *
   * @param pVisitor an optional visitor implementing {@link Visitor}
   * @param pWtx sirix {@link XdmNodeWriteTrx}
   * @see Visitor#processFile(XdmNodeReadTrx) processFile(IReadTransaction)
   */
  private void processFile(final Optional<Visitor<XdmNodeWriteTrx>> pVisitor,
      final XdmNodeWriteTrx pWtx, final Path pFile, final BasicFileAttributes pAttrs) {
    assert pVisitor != null;
    assert pWtx != null;
    if (pVisitor.isPresent()) {
      pVisitor.get().processFile(pWtx, pFile, Optional.of(pAttrs));
    }
  }

  @Override
  public void close() throws SirixException {
    mWtx.commit();
    mWtx.close();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("instances", INSTANCES).add("wtx", mWtx).toString();
  }

  /**
   * Get the path index.
   *
   * @return path index
   */
  public Map<Path, org.sirix.fs.Path> getIndex() {
    return mIndex;
  }

  @Override
  public FileVisitResult visitFileFailed(final Path pFile, final IOException pExc)
      throws IOException {
    LOGWRAPPER.error(pExc.getMessage(), pExc);
    return FileVisitResult.CONTINUE;
  }
}
