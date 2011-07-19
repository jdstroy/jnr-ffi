/*
 * Copyright (C) 2011 Wayne Meissner
 *
 * This file is part of the JNR project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jnr.ffi.provider.jffi;

import com.kenai.jffi.*;
import jnr.ffi.*;
import jnr.ffi.provider.InAccessibleMemoryIO;

/**
 *
 */
class NativeClosurePointer extends InAccessibleMemoryIO {
    private final NativeClosure nativeClosure;
    private final com.kenai.jffi.Closure.Handle handle;


    public NativeClosurePointer(jnr.ffi.Runtime runtime, NativeClosure nativeClosure, com.kenai.jffi.Closure.Handle handle) {
        super(runtime);
        this.nativeClosure = nativeClosure;
        this.handle = handle;
    }

    @Override
    public boolean isDirect() {
        return true;
    }

    @Override
    public long address() {
        return handle.getAddress();
    }

    @Override
    public long size() {
        return 0;
    }

    Callable getCallable() {
        return nativeClosure.get();
    }

    NativeClosure getNativeClosure() {
        return nativeClosure;
    }
}
