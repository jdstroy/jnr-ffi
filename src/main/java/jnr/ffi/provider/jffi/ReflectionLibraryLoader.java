/*
 * Copyright (C) 2012 Wayne Meissner
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

import com.kenai.jffi.Function;
import jnr.ffi.CallingConvention;
import jnr.ffi.LibraryOption;
import jnr.ffi.Runtime;
import jnr.ffi.Variable;
import jnr.ffi.annotations.StdCall;
import jnr.ffi.annotations.Synchronized;
import jnr.ffi.mapper.*;
import jnr.ffi.provider.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static jnr.ffi.provider.jffi.InvokerUtil.*;
import static jnr.ffi.util.Annotations.sortedAnnotationCollection;

/**
 *
 */
class ReflectionLibraryLoader extends LibraryLoader {

    @Override
    <T> T loadLibrary(NativeLibrary library, Class<T> interfaceClass, Map<LibraryOption, ?> libraryOptions) {
        return interfaceClass.cast(Proxy.newProxyInstance(interfaceClass.getClassLoader(),
                new Class[]{ interfaceClass, LoadedLibrary.class }, new NativeInvocationHandler(new LazyLoader<T>(library, interfaceClass, libraryOptions))));
    }

    private static final class SynchronizedInvoker implements Invoker {
        private final Invoker invoker;
        public SynchronizedInvoker(Invoker invoker) {
            this.invoker = invoker;
        }

        @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
        public Object invoke(Object self, Object[] parameters) {
            synchronized (self) {
                return invoker.invoke(self, parameters);
            }
        }
    }

    private static final class FunctionNotFoundInvoker implements Invoker {
        private final Method method;
        private final String functionName;

        private FunctionNotFoundInvoker(Method method, String functionName) {
            this.method = method;
            this.functionName = functionName;
        }

        @Override
        public Object invoke(Object self, Object[] parameters) {
            throw new UnsatisfiedLinkError(String.format("native method '%s' not found for method %s", functionName,  method));
        }
    }

    private static final class GetRuntimeInvoker implements Invoker {
        private final jnr.ffi.Runtime runtime;

        private GetRuntimeInvoker(Runtime runtime) {
            this.runtime = runtime;
        }

        @Override
        public Object invoke(Object self, Object[] parameters) {
            return runtime;
        }
    }

    private static final class LazyLoader<T> extends AbstractMap<Method, Invoker> {
        private final DefaultInvokerFactory invokerFactory = new DefaultInvokerFactory();
        private final jnr.ffi.Runtime runtime = NativeRuntime.getInstance();
        private final AsmClassLoader classLoader = new AsmClassLoader();
        private final SignatureTypeMapper typeMapper;
        private final FunctionMapper functionMapper;
        private final jnr.ffi.CallingConvention libraryCallingConvention;

        private final boolean libraryIsSynchronized;

        @SuppressWarnings("unused")
        private final NativeLibrary library;
        @SuppressWarnings("unused")
        private final Class<T> interfaceClass;
        @SuppressWarnings("unused")
        private final Map<LibraryOption, ?> libraryOptions;

        private LazyLoader(NativeLibrary library, Class<T> interfaceClass, Map<LibraryOption, ?> libraryOptions) {
            this.library = library;
            this.interfaceClass = interfaceClass;
            this.libraryOptions = libraryOptions;

            this.functionMapper = libraryOptions.containsKey(LibraryOption.FunctionMapper)
                    ? (FunctionMapper) libraryOptions.get(LibraryOption.FunctionMapper) : IdentityFunctionMapper.getInstance();

            SignatureTypeMapper typeMapper;
            if (libraryOptions.containsKey(LibraryOption.TypeMapper)) {
                Object tm = libraryOptions.get(LibraryOption.TypeMapper);
                if (tm instanceof SignatureTypeMapper) {
                    typeMapper = (SignatureTypeMapper) tm;
                } else if (tm instanceof TypeMapper) {
                    typeMapper = new SignatureTypeMapperAdapter((TypeMapper) tm);
                } else {
                    throw new IllegalArgumentException("TypeMapper option is not a valid TypeMapper instance");
                }
            } else {
                typeMapper = new NullTypeMapper();
            }

            this.typeMapper = new CompositeTypeMapper(typeMapper,
                    new CachingTypeMapper(new InvokerTypeMapper(new NativeClosureManager(runtime, typeMapper, classLoader), classLoader, NativeLibraryLoader.ASM_ENABLED)));
            libraryCallingConvention = getCallingConvention(interfaceClass, libraryOptions);
            libraryIsSynchronized = interfaceClass.isAnnotationPresent(Synchronized.class);
        }

        @Override
        public Set<Entry<Method, Invoker>> entrySet() {
            throw new UnsupportedOperationException("not implemented");
        }

        @Override
        public synchronized Invoker get(Object key) {

            if (!(key instanceof Method)) {
                throw new IllegalArgumentException("key not instance of Method");
            }

            Method method = (Method) key;
            if (Variable.class.isAssignableFrom(method.getReturnType())) {
                return getVariableAccessor(method);

            } else if (method.getName().equals("getRuntime") && method.getReturnType().isAssignableFrom(NativeRuntime.class)) {
                return new GetRuntimeInvoker(runtime);
            } else {
                return getFunctionInvoker(method);
            }
        }

        private Invoker getFunctionInvoker(Method method) {
            Collection<Annotation> annotations = sortedAnnotationCollection(method.getAnnotations());
            String functionName = functionMapper.mapFunctionName(method.getName(), new NativeFunctionMapperContext(library, annotations));
            long functionAddress = library.getSymbolAddress(functionName);
            if (functionAddress == 0L) {
                return new FunctionNotFoundInvoker(method, functionName);
            }

            FromNativeContext resultContext = new MethodResultContext(NativeRuntime.getInstance(), method);
            SignatureType signatureType = DefaultSignatureType.create(method.getReturnType(), resultContext);
            ResultType resultType = getResultType(runtime, method.getReturnType(),
                    resultContext.getAnnotations(), typeMapper.getFromNativeType(signatureType, resultContext),
                    resultContext);

            ParameterType[] parameterTypes = getParameterTypes(runtime, typeMapper, method);

            // Allow individual methods to set the calling convention to stdcall
            CallingConvention callingConvention = method.isAnnotationPresent(StdCall.class)
                    ? CallingConvention.STDCALL : libraryCallingConvention;

            Function function = new Function(functionAddress,
                    getCallContext(resultType, parameterTypes, callingConvention, InvokerUtil.requiresErrno(method)));

            Invoker invoker = invokerFactory.createInvoker(runtime, library, function, resultType, parameterTypes);

            //
            // If either the method or the library is specified as requiring
            // synchronization, then wrap the raw invoker in a synchronized proxy
            //
            return libraryIsSynchronized || method.isAnnotationPresent(Synchronized.class)
                    ? new SynchronizedInvoker(invoker) : invoker;
        }

        private Invoker getVariableAccessor(Method method) {
            Collection<Annotation> annotations = sortedAnnotationCollection(method.getAnnotations());

            String functionName = functionMapper.mapFunctionName(method.getName(), new NativeFunctionMapperContext(library, annotations));
            long symbolAddress = library.getSymbolAddress(functionName);
            if (symbolAddress == 0L) {
                return new FunctionNotFoundInvoker(method, functionName);
            }
            Variable variable = ReflectionVariableAccessorGenerator.createVariableAccessor(runtime, method, symbolAddress,
                    typeMapper, annotations);
            return new VariableAcccessorInvoker(variable);
        }


        private static final class VariableAcccessorInvoker implements Invoker {
            private final Variable variable;

            private VariableAcccessorInvoker(Variable variable) {
                this.variable = variable;
            }

            @Override
            public Object invoke(Object self, Object[] parameters) {
                return variable;
            }
        }
    }
}
