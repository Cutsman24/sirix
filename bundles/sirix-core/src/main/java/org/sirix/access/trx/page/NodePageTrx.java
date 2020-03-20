/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.access.trx.page;

import org.sirix.access.ResourceConfiguration;
import org.sirix.access.trx.node.CommitCredentials;
import org.sirix.access.trx.node.IndexController;
import org.sirix.access.trx.node.Restore;
import org.sirix.access.trx.node.xml.XmlIndexController;
import org.sirix.api.PageReadOnlyTrx;
import org.sirix.api.PageTrx;
import org.sirix.cache.PageContainer;
import org.sirix.cache.TransactionIntentLog;
import org.sirix.exception.SirixIOException;
import org.sirix.io.Writer;
import org.sirix.node.DeletedNode;
import org.sirix.node.NodeKind;
import org.sirix.node.delegates.NodeDelegate;
import org.sirix.node.interfaces.DataRecord;
import org.sirix.node.interfaces.Node;
import org.sirix.page.*;
import org.sirix.page.interfaces.KeyValuePage;
import org.sirix.page.interfaces.Page;
import org.sirix.settings.Constants;
import org.sirix.settings.Fixed;
import org.sirix.settings.VersioningType;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <h1>PageWriteTrx</h1>
 *
 * <p>
 * Implements the {@link PageTrx} interface to provide write capabilities to the persistent storage
 * layer.
 * </p>
 *
 * @author Marc Kramis, Seabix AG
 * @author Sebastian Graf, University of Konstanz
 * @author Johannes Lichtenberger
 */
final class NodePageTrx extends AbstractForwardingPageReadOnlyTrx
    implements PageTrx<Long, DataRecord, UnorderedKeyValuePage> {

  /**
   * Page writer to serialize.
   */
  private final Writer pageWriter;

  /**
   * Transaction intent log.
   */
  TransactionIntentLog trxIntentLog;

  /**
   * Last reference to the actual revRoot.
   */
  private final RevisionRootPage newRoot;

  /**
   * {@link NodePageReadOnlyTrx} instance.
   */
  private final NodePageReadOnlyTrx pageRtx;

  /**
   * Determines if a log must be replayed or not.
   */
  private Restore mRestore = Restore.NO;

  /**
   * Determines if transaction is closed.
   */
  private boolean isClosed;

  /**
   * {@link XmlIndexController} instance.
   */
  private final IndexController<?, ?> indexController;

  /**
   * The tree modifier.
   */
  private final TreeModifier treeModifier;

  /**
   * The revision to represent.
   */
  private final int representRevision;

  /**
   * {@code true} if this page write trx will be bound to a node trx, {@code false} otherwise
   */
  private final boolean isBoundToNodeTrx;

  /**
   * Constructor.
   *
   * @param writer            the page writer
   * @param log               the transaction intent log
   * @param revisionRootPage  the revision root page
   * @param pageRtx           the page reading transaction used as a delegate
   * @param indexController   the index controller, which is used to update indexes
   * @param representRevision the revision to represent
   * @param isBoundToNodeTrx  {@code true} if this page write trx will be bound to a node trx,
   *                          {@code false} otherwise
   */
  NodePageTrx(final TreeModifier treeModifier, final Writer writer, final TransactionIntentLog log,
      final RevisionRootPage revisionRootPage, final NodePageReadOnlyTrx pageRtx,
      final IndexController<?, ?> indexController, final int representRevision, final boolean isBoundToNodeTrx) {
    this.treeModifier = checkNotNull(treeModifier);
    pageWriter = checkNotNull(writer);
    this.trxIntentLog = checkNotNull(log);
    newRoot = checkNotNull(revisionRootPage);
    this.pageRtx = checkNotNull(pageRtx);
    this.indexController = checkNotNull(indexController);
    checkArgument(representRevision >= 0, "The represented revision must be >= 0.");
    this.representRevision = representRevision;
    this.isBoundToNodeTrx = isBoundToNodeTrx;
  }

  @Override
  public int getRevisionToRepresent() {
    return representRevision;
  }

  @Override
  public TransactionIntentLog getTrxIntentLog() {
    return trxIntentLog;
  }

  @Override
  public void restore(final Restore restore) {
    mRestore = checkNotNull(restore);
  }

  @Override
  public DataRecord prepareEntryForModification(final @Nonnegative Long recordKey, final PageKind pageKind,
      final int index) {
    pageRtx.assertNotClosed();
    checkNotNull(recordKey);
    checkArgument(recordKey >= 0, "recordKey must be >= 0!");
    checkNotNull(pageKind);

    final long recordPageKey = pageRtx.pageKey(recordKey);
    final PageContainer cont = prepareRecordPage(recordPageKey, index, pageKind);

    DataRecord record = ((UnorderedKeyValuePage) cont.getModified()).getValue(recordKey);
    if (record == null) {
      final DataRecord oldRecord = ((UnorderedKeyValuePage) cont.getComplete()).getValue(recordKey);
      if (oldRecord == null) {
        throw new SirixIOException("Cannot retrieve record from cache!");
      }
      record = oldRecord;
      ((UnorderedKeyValuePage) cont.getModified()).setEntry(record.getNodeKey(), record);
    }
    return record;
  }

  @Override
  public DataRecord createEntry(final Long key, final DataRecord record, final PageKind pageKind, final int index) {
    pageRtx.assertNotClosed();
    // Allocate record key and increment record count.
    long recordKey;
    switch (pageKind) {
      case RECORDPAGE:
        recordKey = newRoot.incrementAndGetMaxNodeKey();
        break;
      case PATHSUMMARYPAGE:
        final PathSummaryPage pathSummaryPage = ((PathSummaryPage) newRoot.getPathSummaryPageReference().getPage());
        recordKey = pathSummaryPage.incrementAndGetMaxNodeKey(index);
        break;
      case CASPAGE:
        final CASPage casPage = ((CASPage) newRoot.getCASPageReference().getPage());
        recordKey = casPage.incrementAndGetMaxNodeKey(index);
        break;
      case PATHPAGE:
        final PathPage pathPage = ((PathPage) newRoot.getPathPageReference().getPage());
        recordKey = pathPage.incrementAndGetMaxNodeKey(index);
        break;
      case NAMEPAGE:
        final NamePage namePage = ((NamePage) newRoot.getNamePageReference().getPage());
        recordKey = namePage.incrementAndGetMaxNodeKey(index);
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException();
    }

    final long recordPageKey = pageRtx.pageKey(recordKey);
    final PageContainer cont = prepareRecordPage(recordPageKey, index, pageKind);
    @SuppressWarnings("unchecked")
    final KeyValuePage<Long, DataRecord> modified = (KeyValuePage<Long, DataRecord>) cont.getModified();
    modified.setEntry(key, record);
    return record;
  }

  @Override
  public void removeEntry(final Long recordKey, @Nonnull final PageKind pageKind, final int index) {
    pageRtx.assertNotClosed();
    final long nodePageKey = pageRtx.pageKey(recordKey);
    final PageContainer cont = prepareRecordPage(nodePageKey, index, pageKind);
    final Optional<DataRecord> node = getRecord(recordKey, pageKind, index);
    if (node.isPresent()) {
      final DataRecord nodeToDel = node.get();
      final Node delNode = new DeletedNode(
          new NodeDelegate(nodeToDel.getNodeKey(), -1, null, null, pageRtx.getRevisionNumber(), null));
      ((UnorderedKeyValuePage) cont.getModified()).setEntry(delNode.getNodeKey(), delNode);
      ((UnorderedKeyValuePage) cont.getComplete()).setEntry(delNode.getNodeKey(), delNode);
    } else {
      throw new IllegalStateException("Node not found!");
    }
  }

  @Override
  public Optional<DataRecord> getRecord(final @Nonnegative long recordKey, final PageKind pageKind,
      final @Nonnegative int index) {
    pageRtx.assertNotClosed();
    checkArgument(recordKey >= Fixed.NULL_NODE_KEY.getStandardProperty());
    checkNotNull(pageKind);
    // Calculate page.
    final long recordPageKey = pageRtx.pageKey(recordKey);

    final PageContainer pageCont = prepareRecordPage(recordPageKey, index, pageKind);
    if (pageCont.equals(PageContainer.emptyInstance())) {
      return pageRtx.getRecord(recordKey, pageKind, index);
    } else {
      DataRecord node = ((UnorderedKeyValuePage) pageCont.getModified()).getValue(recordKey);
      if (node == null) {
        node = ((UnorderedKeyValuePage) pageCont.getComplete()).getValue(recordKey);
      }
      return pageRtx.checkItemIfDeleted(node);
    }
  }

  @Override
  public String getName(final int nameKey, final NodeKind nodeKind) {
    pageRtx.assertNotClosed();
    final NamePage currentNamePage = getNamePage(newRoot);
    return (currentNamePage == null || currentNamePage.getName(nameKey, nodeKind, pageRtx) == null) ? pageRtx.getName(
        nameKey, nodeKind) : currentNamePage.getName(nameKey, nodeKind, pageRtx);
  }

  @Override
  public int createNameKey(final @Nullable String name, final NodeKind nodeKind) {
    pageRtx.assertNotClosed();
    checkNotNull(nodeKind);
    final String string = (name == null ? "" : name);
    final NamePage namePage = getNamePage(newRoot);
    return namePage.setName(string, nodeKind, this);
  }

  @Override
  public void commit(final @Nullable PageReference reference) {
    if (reference == null)
      return;

//    mLog.setEvict(false);

    final PageContainer container = trxIntentLog.get(reference, this);

    trxIntentLog.remove(reference);

    Page page = null;

    if (container != null) {
      page = container.getModified();
    }

    if (page == null) {
      return;
    }

    reference.setPage(page);

    // Recursively commit indirectly referenced pages and then write self.f
    page.commit(this);
    pageWriter.write(reference);

    // Remove page reference.
    reference.setPage(null);
  }

  @Override
  public UberPage commit(final String commitMessage) {
    pageRtx.assertNotClosed();

    pageRtx.resourceManager.getCommitLock().lock();

    final Path commitFile = pageRtx.resourceManager.getCommitFile();
    commitFile.toFile().deleteOnExit();
    // Issues with windows that it's not created in the first time?
    while (!Files.exists(commitFile)) {
      try {
        Files.createFile(commitFile);
      } catch (final IOException e) {
        throw new SirixIOException(e);
      }
    }

    final PageReference uberPageReference = new PageReference();
    final UberPage uberPage = getUberPage();
    uberPageReference.setPage(uberPage);
    final int revision = uberPage.getRevisionNumber();

    pageRtx.resourceManager.getUser().ifPresent(user -> getActualRevisionRootPage().setUser(user));

    if (commitMessage != null)
      getActualRevisionRootPage().setCommitMessage(commitMessage);

    // Recursively write indirectly referenced pages.
    uberPage.commit(this);

    uberPageReference.setPage(uberPage);
    pageWriter.writeUberPageReference(uberPageReference);
    uberPageReference.setPage(null);

    final Path indexes = pageRtx.getResourceManager().getResourceConfig().resourcePath.resolve(
        ResourceConfiguration.ResourcePaths.INDEXES.getPath()).resolve(revision + ".xml");

    if (!Files.exists(indexes)) {
      try {
        Files.createFile(indexes);
      } catch (final IOException e) {
        throw new SirixIOException(e);
      }
    }

    try (final OutputStream out = new FileOutputStream(indexes.toFile())) {
      indexController.serialize(out);
    } catch (final IOException e) {
      throw new SirixIOException("Index definitions couldn't be serialized!", e);
    }

    trxIntentLog.truncate();

    // Delete commit file which denotes that a commit must write the log in the data file.
    try {
      Files.delete(commitFile);
    } catch (final IOException e) {
      throw new SirixIOException("Commit file couldn't be deleted!");
    }

    final UberPage commitedUberPage = (UberPage) pageWriter.read(pageWriter.readUberPageReference(), pageRtx);
    pageRtx.resourceManager.getCommitLock().unlock();
    return commitedUberPage;
  }

  @Override
  public UberPage commit() {
    return commit((String) null);
  }

  @Override
  public UberPage rollback() {
    pageRtx.assertNotClosed();
    trxIntentLog.truncate();
    return (UberPage) pageWriter.read(pageWriter.readUberPageReference(), pageRtx);
  }

  @Override
  public void close() {
    if (!isClosed) {
      pageRtx.assertNotClosed();

      final UberPage lastUberPage = (UberPage) pageWriter.read(pageWriter.readUberPageReference(), pageRtx);

      pageRtx.resourceManager.setLastCommittedUberPage(lastUberPage);

      if (!isBoundToNodeTrx)
        pageRtx.resourceManager.closePageWriteTransaction(pageRtx.getTrxId());

      trxIntentLog.close();
      pageRtx.close();
      pageWriter.close();
      isClosed = true;
    }
  }

  /**
   * Prepare record page.
   *
   * @param recordPageKey the key of the record page
   * @param indexNumber   the index number if it's a record-page of an index, {@code -1}, else
   * @param pageKind      the kind of page (used to determine the right subtree)
   * @return {@link PageContainer} instance
   * @throws SirixIOException if an I/O error occurs
   */
  private PageContainer prepareRecordPage(final @Nonnegative long recordPageKey, final int indexNumber,
      final PageKind pageKind) {
    assert recordPageKey >= 0;
    assert pageKind != null;

    final PageReference pageReference = pageRtx.getPageReference(newRoot, pageKind, indexNumber);

    // Get the reference to the unordered key/value page storing the records.
    final PageReference reference = treeModifier.prepareLeafOfTree(pageRtx, trxIntentLog,
                                                                    getUberPage().getPageCountExp(pageKind),
                                                                    pageReference, recordPageKey, indexNumber, pageKind,
        newRoot);

    PageContainer pageContainer = trxIntentLog.get(reference, this);

    if (pageContainer.equals(PageContainer.emptyInstance())) {
      if (reference.getKey() == Constants.NULL_ID_LONG) {
        final UnorderedKeyValuePage completePage = new UnorderedKeyValuePage(recordPageKey, pageKind,
                                                                             Constants.NULL_ID_LONG, pageRtx);
        final UnorderedKeyValuePage modifyPage = new UnorderedKeyValuePage(pageRtx, completePage);
        pageContainer = PageContainer.getInstance(completePage, modifyPage);
      } else {
        pageContainer = dereferenceRecordPageForModification(reference);
      }

      assert pageContainer != null;

      switch (pageKind) {
        case RECORDPAGE:
        case PATHSUMMARYPAGE:
        case PATHPAGE:
        case CASPAGE:
        case NAMEPAGE:
          appendLogRecord(reference, pageContainer);
          break;
        // $CASES-OMITTED$
        default:
          throw new IllegalStateException("Page kind not known!");
      }
    }

    return pageContainer;
  }

  /**
   * Dereference record page reference.
   *
   * @param reference reference to leaf, that is the record page
   * @return dereferenced page
   */
  private PageContainer dereferenceRecordPageForModification(final PageReference reference) {
    final List<UnorderedKeyValuePage> revs = pageRtx.getSnapshotPages(reference);
    final VersioningType revisioning = pageRtx.resourceManager.getResourceConfig().revisioningType;
    final int mileStoneRevision = pageRtx.resourceManager.getResourceConfig().numberOfRevisionsToRestore;
    return revisioning.combineRecordPagesForModification(revs, mileStoneRevision, pageRtx, reference);
  }

  @Override
  public RevisionRootPage getActualRevisionRootPage() {
    return newRoot;
  }

  @Override
  protected PageReadOnlyTrx delegate() {
    return pageRtx;
  }

  @Override
  public PageReadOnlyTrx getPageReadTrx() {
    return pageRtx;
  }

  @Override
  public PageTrx<Long, DataRecord, UnorderedKeyValuePage> appendLogRecord(final PageReference reference,
      final PageContainer pageContainer) {
    checkNotNull(pageContainer);
    trxIntentLog.put(reference, pageContainer);
    return this;
  }

  @Override
  public PageContainer getLogRecord(final PageReference reference) {
    checkNotNull(reference);
    return trxIntentLog.get(reference, this);
  }

  @Override
  public PageTrx<Long, DataRecord, UnorderedKeyValuePage> truncateTo(final int revision) {
    pageWriter.truncateTo(revision);
    return this;
  }

  @Override
  public long getTrxId() {
    return pageRtx.getTrxId();
  }

  @Override
  public CommitCredentials getCommitCredentials() {
    return pageRtx.getCommitCredentials();
  }

}
