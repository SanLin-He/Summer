package com.swingfrog.summer.server;

import com.google.protobuf.Message;
import com.swingfrog.summer.annotation.Optional;
import com.swingfrog.summer.annotation.Remote;
import com.swingfrog.summer.ioc.ContainerMgr;
import com.swingfrog.summer.ioc.MethodParameterName;
import com.swingfrog.summer.protocol.protobuf.Protobuf;
import com.swingfrog.summer.protocol.protobuf.ProtobufRequest;
import com.swingfrog.summer.protocol.protobuf.ProtobufResponse;
import com.swingfrog.summer.server.async.AsyncResponse;
import com.swingfrog.summer.server.async.ProcessResult;
import com.swingfrog.summer.server.exception.CodeException;
import com.swingfrog.summer.server.exception.SessionException;
import com.swingfrog.summer.struct.AutowireParam;
import com.swingfrog.summer.util.ProtobufUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class RemoteProtobufDispatchMgr {

    private static final Logger log = LoggerFactory.getLogger(RemoteProtobufDispatchMgr.class);

    private final Map<Integer, RemoteMethod> remoteMethodMap;


    private static class SingleCase {
        public static final RemoteProtobufDispatchMgr INSTANCE = new RemoteProtobufDispatchMgr();
    }

    private RemoteProtobufDispatchMgr() {
        remoteMethodMap = new HashMap<>();
    }

    public static RemoteProtobufDispatchMgr get() {
        return RemoteProtobufDispatchMgr.SingleCase.INSTANCE;
    }

    public void init() throws Exception {
        Iterator<Class<?>> ite = ContainerMgr.get().iteratorRemoteList();
        while (ite.hasNext()) {
            Class<?> clazz = ite.next();
            Remote remote = clazz.getAnnotation(Remote.class);
            if (!remote.protobuf()) {
                continue;
            }
            log.info("server register remote protobuf {}", clazz.getSimpleName());
            RemoteClass remoteClass = new RemoteClass(clazz);
            Method[] methods = clazz.getDeclaredMethods();
            MethodParameterName mpn = new MethodParameterName(clazz);
            for (Method method : methods) {
                RemoteMethod remoteMethod = new RemoteMethod(remoteClass, method, mpn);
                int messageId = ProtobufUtil.getMessageId(remoteMethod.getMessageTemplate());
                if (remoteMethodMap.putIfAbsent(messageId, remoteMethod) != null) {
                    throw new RuntimeException("protobuf message repeat");
                }
            }
        }
    }

    public Method getMethod(ProtobufRequest req) {
        RemoteMethod remoteMethod = remoteMethodMap.get(req.getId());
        if (remoteMethod != null) {
            return remoteMethod.getMethod();
        }
        return null;
    }

    private Object invoke(ServerContext serverContext, ProtobufRequest req, Protobuf data, AutowireParam autowireParam) throws Throwable {
        Map<Class<?>, Object> objForTypes = autowireParam.getTypes();
        Map<String, Object> objForNames = autowireParam.getNames();
        int messageId = req.getId();
        RemoteMethod remoteMethod = remoteMethodMap.get(messageId);
        if (remoteMethod == null) {
            throw new CodeException(SessionException.METHOD_NOT_EXIST);
        }
        RemoteClass remoteClass = remoteMethod.getRemoteClass();
        if (remoteClass.isFilter() && !remoteClass.getServerName().equals(serverContext.getConfig().getServerName())) {
            throw new CodeException(SessionException.REMOTE_WAS_PROTECTED);
        }
        Message message = ProtobufUtil.parseMessage(remoteMethod.getMessageTemplate(), data.getBytes());
        int messageIndex = remoteMethod.getMessageIndex();
        Object remoteObj = ContainerMgr.get().getDeclaredComponent(remoteClass.getClazz());
        Method remoteMod = remoteMethod.getMethod();
        String[] params = remoteMethod.getParams();
        Parameter[] parameters = remoteMethod.getParameters();
        boolean auto = ContainerMgr.get().isAutowiredParameter(remoteClass.getClazz());
        Object[] obj = new Object[params.length];
        try {
            for (int i = 0; i < parameters.length; i++) {
                String param = params[i];
                Parameter parameter = parameters[i];
                Type type = parameter.getParameterizedType();
                if (i == messageIndex) {
                    obj[i] = message;
                } else {
                    if (auto) {
                        Class<?> typeClazz = parameter.getType();
                        if (objForTypes != null && objForTypes.containsKey(typeClazz)) {
                            obj[i] = objForTypes.get(typeClazz);
                        } else if (objForNames != null && objForNames.containsKey(param)) {
                            obj[i] = objForNames.get(param);
                        } else {
                            obj[i] = ContainerMgr.get().getComponent(typeClazz);
                            if (obj[i] == null) {
                                try {
                                    obj[i] = ((Class<?>) type).newInstance();
                                } catch (Exception e) {
                                    log.error(e.getMessage(), e);
                                }
                            }
                        }
                    }
                }
                if (obj[i] == null) {
                    if (!parameter.isAnnotationPresent(Optional.class)) {
                        throw new CodeException(SessionException.PARAMETER_ERROR);
                    }
                }
            }
        } catch (Exception e) {
            throw new CodeException(SessionException.PARAMETER_ERROR);
        }
        try {
            return remoteMod.invoke(remoteObj, obj);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    public ProcessResult<ProtobufResponse> process(ServerContext serverContext, ProtobufRequest req, Protobuf data, SessionContext sctx,
                                                   AutowireParam autowireParam) throws Throwable {
        Map<Class<?>, Object> objForTypes = autowireParam.getTypes();
        objForTypes.putIfAbsent(SessionContext.class, sctx);
        objForTypes.putIfAbsent(ProtobufRequest.class, req);
        Object result = invoke(serverContext, req, data, autowireParam);
        if (result instanceof AsyncResponse) {
            return new ProcessResult<>(true, null);
        }
        if (result instanceof Message) {
            return new ProcessResult<>(false, ProtobufResponse.of(req.getId(), (Message) result));
        }
        return null;
    }

    private static class RemoteMethod {
        private final RemoteClass remoteClass;
        private final Method method;
        private final String[] params;
        private final Parameter[] parameters;
        private Message messageTemplate;
        private int messageIndex;
        public RemoteMethod(RemoteClass remoteClass, Method method, MethodParameterName mpn) throws Exception {
            this.remoteClass = remoteClass;
            this.method = method;
            params = mpn.getParameterNameByMethod(method);
            parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                Class<?> typeClazz = parameters[i].getType();
                if (Message.class.isAssignableFrom(typeClazz)) {
                    messageTemplate = ProtobufUtil.getDefaultInstance(typeClazz);
                    messageIndex = i;
                    break;
                }
            }
            if (messageTemplate == null)
                throw new RuntimeException("not found protobuf message in remote method");
        }
        public RemoteClass getRemoteClass() {
            return remoteClass;
        }
        public Method getMethod() {
            return method;
        }
        public String[] getParams() {
            return params;
        }
        public Parameter[] getParameters() {
            return parameters;
        }
        public Message getMessageTemplate() {
            return messageTemplate;
        }
        public int getMessageIndex() {
            return messageIndex;
        }
    }

    private static class RemoteClass {
        private final boolean filter;
        private final String serverName;
        private final Class<?> clazz;
        public RemoteClass(Class<?> clazz) {
            this.clazz = clazz;
            filter = clazz.getAnnotation(Remote.class).filter();
            serverName = clazz.getAnnotation(Remote.class).serverName();
        }
        public boolean isFilter() {
            return filter;
        }
        public String getServerName() {
            return serverName;
        }
        public Class<?> getClazz() {
            return clazz;
        }
    }

}