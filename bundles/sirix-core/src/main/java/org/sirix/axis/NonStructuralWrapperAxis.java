/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.axis;

import org.sirix.api.Axis;
import org.sirix.api.xml.XmlNodeReadOnlyTrx;
import org.sirix.settings.Fixed;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Adds non-structural nodes (that is namespaces and attributes) to an axis.
 *
 * @author Johannes Lichtenberger
 */
public final class NonStructuralWrapperAxis extends AbstractAxis {

  /** Parent axis. */
  private final Axis parentAxis;

  /** Namespace index. */
  private int namespaceIndex;

  /** Attribute index. */
  private int attributeIndex;

  /** Determines if transaction has moved to the next structural node at first. */
  private boolean isFirst;

  /**
   * Constructor initializing internal state.
   *
   * @param parentAxis inner nested axis
   */
  public NonStructuralWrapperAxis(final Axis parentAxis) {
    super(parentAxis.asXdmNodeReadTrx());
    this.parentAxis = checkNotNull(parentAxis);
  }

  @Override
  public void reset(final long nodeKey) {
    super.reset(nodeKey);
    if (parentAxis != null) {
      parentAxis.reset(nodeKey);
    }
    namespaceIndex = 0;
    attributeIndex = 0;
    isFirst = true;
  }

  @Override
  protected long nextKey() {
    final XmlNodeReadOnlyTrx trx = parentAxis.asXdmNodeReadTrx();
    if (parentAxis.isSelfIncluded() == IncludeSelf.NO || !isFirst) {
      final long nodeKey = nonStructural(trx);
      if (nodeKey != Fixed.NULL_NODE_KEY.getStandardProperty()) {
        return nodeKey;
      }
    }

    if (parentAxis.hasNext()) {
      final long key = parentAxis.next();
      isFirst = false;
      namespaceIndex = 0;
      attributeIndex = 0;
      return key;
    }

    return done();
  }

  /**
   * Determine if non structural nodes must be emitted.
   *
   * @param trx Sirix {@link XmlNodeReadOnlyTrx}
   * @return the node key of the non structural node, or the {@code NULL_NODE_KEY}
   */
  private long nonStructural(final XmlNodeReadOnlyTrx trx) {
    if (trx.isNamespace()) {
      trx.moveToParent();
    }
    if (trx.isElement() && namespaceIndex < trx.getNamespaceCount()) {
      trx.moveToNamespace(namespaceIndex++);
      return trx.getNodeKey();
    }
    if (trx.isAttribute()) {
      trx.moveToParent();
    }
    if (trx.isElement() && attributeIndex < trx.getAttributeCount()) {
      trx.moveToAttribute(attributeIndex++);
      return trx.getNodeKey();
    }
    return Fixed.NULL_NODE_KEY.getStandardProperty();
  }
}
