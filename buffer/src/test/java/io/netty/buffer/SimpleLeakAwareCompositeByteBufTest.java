/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer;

import org.junit.Assert;
import org.junit.Test;

public class SimpleLeakAwareCompositeByteBufTest extends WrappedCompositeByteBufTest {

    private final Class<? extends ByteBuf> clazz = leakClass();

    @Override
    protected WrappedCompositeByteBuf wrap(CompositeByteBuf buffer) {
        return new SimpleLeakAwareCompositeByteBuf(buffer, new NoopResourceLeakTracker<ByteBuf>());
    }

    protected Class<? extends ByteBuf> leakClass() {
        return SimpleLeakAwareByteBuf.class;
    }

   @Test
    public void testWrapSlice() {
        assertWrapped(newBuffer(8).slice());
    }

    @Test
    public void testWrapSlice2() {
        assertWrapped(newBuffer(8).slice(0, 1));
    }

    @Test
    public void testWrapReadSlice() {
        assertWrapped(newBuffer(8).readSlice(1));
    }

    @Test
    public void testWrapDuplicate() {
        assertWrapped(newBuffer(8).duplicate());
    }

    protected final void assertWrapped(ByteBuf buf) {
        try {
            Assert.assertSame(clazz, buf.getClass());
        } finally {
            buf.release();
        }
    }
}
