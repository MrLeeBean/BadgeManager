package ms.imf.redpoint.compiler;


import com.google.auto.service.AutoService;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import ms.imf.redpoint.annotation.Path;
import ms.imf.redpoint.annotation.PathAptGlobalConfig;
import ms.imf.redpoint.annotation.Plugin;
import ms.imf.redpoint.compiler.plugin.AptProcessException;
import ms.imf.redpoint.compiler.plugin.ParsedNodeSchemaHandlePlugin;
import ms.imf.redpoint.compiler.plugin.PathEntity;
import ms.imf.redpoint.compiler.plugin.PluginContext;
import ms.imf.redpoint.entity.NodeSchema;

@AutoService(Processor.class)
public class PathNodeAnnotationProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        final LinkedHashSet<String> supportedTypes = new LinkedHashSet<>();

        for (Class annotationClass : new Class[]{Path.class, PathAptGlobalConfig.class}) {
            supportedTypes.add(annotationClass.getCanonicalName());
        }

        return supportedTypes;
    }

    private final List<PathEntity> allPathEntities = new LinkedList<>();

    private TypeElement lastPathAptGlobalConfigAnnotationHost;
    private PathAptGlobalConfig pathAptGlobalConfig;
    private AnnotationMirror pathAptGlobalConfigMirror;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            return processRaw(roundEnv);
        } catch (Exception e) {
            showErrorTip(new AptProcessException(String.format("unexpected exception on processor: %s", e.getMessage()), e));
            return false;
        }
    }

    private boolean processRaw(RoundEnvironment roundEnv) {
        // check config
        try {
            checkPathAptGlobalConfig(roundEnv);
        } catch (AptProcessException e) {
            showErrorTip(e);
            return false;
        }

        final Set<TypeElement> pathAnnotationTypes = ElementFilter.typesIn(roundEnv.getElementsAnnotatedWith(Path.class));

        // parse paths
        final PathNodeAnnotationParser pathNodeAnnotationParser = new PathNodeAnnotationParser(processingEnv.getElementUtils());
        final List<PathEntity> pathEntities;
        try {
            pathEntities = pathNodeAnnotationParser.parsePaths(pathAnnotationTypes);
        } catch (AptProcessException e) {
            showErrorTip(new AptProcessException(String.format("found error on parsing @Path: %s", e.getMessage()), e));
            return false;
        }

        if (pathAptGlobalConfig != null) {
            // process each apt round plugin
            try {
                processPlugins(
                        "each apt round plugin",
                        PathNodeAnnotationParser.<List<AnnotationValue>>getAnnotionMirrorValue(pathAptGlobalConfigMirror, "eachAptRoundPlugins" /* todo runtime check */),
                        pathAptGlobalConfig.eachAptRoundPlugins(),
                        pathEntities
                );
            } catch (AptProcessException e) {
                showErrorTip(e);
                return false;
            }
        }

        // compose each round data
        allPathEntities.addAll(pathEntities);

        if (!roundEnv.processingOver()) {
            return true;
        }
        // process finish task

        final List<PathEntity> treePathEntities = PathNodeAnnotationParser.convertPathTree(allPathEntities);

        // root node repeat check
        try {
            pathNodeTypeRepeatCheck(treePathEntities);
        } catch (AptProcessException e) {
            showErrorTip(e);
            return false;
        }

        // path node type check
        try {
            checkPathNodeType(allPathEntities, treePathEntities);
        } catch (AptProcessException e) {
            showErrorTip(e);
            return false;
        }

        if (pathAptGlobalConfig != null) {
            // process last apt round plugin
            try {
                processPlugins(
                        "last apt round plugin",
                        PathNodeAnnotationParser.<List<AnnotationValue>>getAnnotionMirrorValue(pathAptGlobalConfigMirror, "lastAptRoundPlugins" /* todo runtime check */),
                        pathAptGlobalConfig.lastAptRoundPlugins(),
                        allPathEntities
                );
            } catch (AptProcessException e) {
                showErrorTip(e);
                return false;
            }
        }

        return true;
    }

    private void checkPathNodeType(List<PathEntity> allPathEntities, List<PathEntity> treePathEntities) throws AptProcessException {
        for (PathEntity pathEntity : allPathEntities) {
            boolean isRootNode = treePathEntities.contains(pathEntity);
            switch (pathEntity.host.getAnnotation(Path.class).type()) {
                case ROOT_NODE:
                    if (!isRootNode) {
                        PathEntity parentPathEntiity = findParentPathEntity(pathEntity, treePathEntities);
                        throw new AptProcessException(
                                String.format(
                                        "'%s's PathNode should be a root node, but it's a sub node, it has a parent '%s', please check path's link relations",
                                        pathEntity.host.getQualifiedName(),
                                        parentPathEntiity.host.getQualifiedName()
                                ),
                                pathEntity.host
                        );
                    }
                    break;
                case SUB_NODE:
                    if (isRootNode) {
                        throw new AptProcessException(
                                String.format(
                                        "'%s's PathNode should be a sub node, but it's a root node, please check path's link relations",
                                        pathEntity.host.getQualifiedName()
                                ),
                                pathEntity.host
                        );
                    }
                    break;
                case UNLIMIT:
                default:
                    break;
            }
        }
    }

    private PathEntity findParentPathEntity(PathEntity sourcePathEntity, List<PathEntity> treePathEntities) {
        for (PathEntity pathEntity : treePathEntities) {
            PathEntity result = findParentPathEntity(sourcePathEntity, pathEntity);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private PathEntity findParentPathEntity(PathEntity sourcePathEntity, PathEntity pathEntity) {
        for (PathEntity.Node node : pathEntity.nodes) {
            if (node.subRef == sourcePathEntity) {
                return pathEntity;
            } else {
                return findParentPathEntity(sourcePathEntity, node.subRef);
            }
        }
        return null;
    }

    private void processPlugins(String pluginProcessDesc, List<AnnotationValue> pluginAnnotationValues, Plugin[] plugins, final List<PathEntity> allPathEntities) throws AptProcessException {

        if (pluginAnnotationValues == null
                || pluginAnnotationValues.isEmpty()
                || plugins == null
                || plugins.length <= 0) {
            return;
        }

        for (int i = 0; i < plugins.length; i++) {
            Plugin pluginAnnotation = plugins[i];
            AnnotationValue pluginAnnotationValue = pluginAnnotationValues.get(i);

            final String pluginClassName = ((TypeElement) ((DeclaredType) PathNodeAnnotationParser.getAnnotionMirrorValue((AnnotationMirror) pluginAnnotationValue.getValue(), "value" /* todo runtime check */)).asElement()).getQualifiedName().toString();
            final String[] pluginClassArguments = pluginAnnotation.args();

            final ParsedNodeSchemaHandlePlugin plugin;
            try {
                plugin = getPluginInstance(pluginClassName);
            } catch (Exception e) {
                showErrorTip(
                        new AptProcessException(
                                String.format("found error on create plugin instance: %s", e.getMessage()),
                                lastPathAptGlobalConfigAnnotationHost, pathAptGlobalConfigMirror, pluginAnnotationValue
                        )
                );
                return;
            }

            try {

                @SuppressWarnings("unchecked") final List<PathEntity>[] treePathEntities = new List[1];
                @SuppressWarnings("unchecked") final List<NodeSchema>[] treeNodeSchemas = new List[1];

                plugin.onParsed(new PluginContext() {
                    @Override
                    public ProcessingEnvironment processingEnvironment() {
                        return processingEnv;
                    }

                    @Override
                    public String[] args() {
                        return pluginClassArguments;
                    }

                    @Override
                    public List<PathEntity> allPathEntities() {
                        return allPathEntities;
                    }

                    @Override
                    public List<PathEntity> treePathEntities() {
                        if (treePathEntities[0] == null) {
                            treePathEntities[0] = PathNodeAnnotationParser.convertPathTree(allPathEntities());
                        }
                        return treePathEntities[0];
                    }

                    @Override
                    public List<NodeSchema> treeNodeSchemas() {
                        if (treeNodeSchemas[0] == null) {
                            treeNodeSchemas[0] = PathNodeAnnotationParser.generateNodeSchemaTree(treePathEntities());
                        }
                        return treeNodeSchemas[0];
                    }
                });
            } catch (Exception e) {
                throw new AptProcessException(
                        String.format("found error on process %s '%s': %s", pluginProcessDesc, plugin.getClass().getCanonicalName(), e.getMessage()),
                        e, lastPathAptGlobalConfigAnnotationHost, pathAptGlobalConfigMirror, pluginAnnotationValue
                );
            }
        }
    }

    private ParsedNodeSchemaHandlePlugin getPluginInstance(String pluginClassName) {

        final Class<?> pluginClass;
        try {
            pluginClass = Class.forName(pluginClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(String.format("can't find plugin's class '%s', please check classpath", pluginClassName), e);
        }

        if (!ParsedNodeSchemaHandlePlugin.class.isAssignableFrom(pluginClass)) {
            throw new IllegalArgumentException(String.format("class '%s' is not %s's subtype, please check plugin's type", pluginClassName, ParsedNodeSchemaHandlePlugin.class.getCanonicalName()));
        }

        try {
            return (ParsedNodeSchemaHandlePlugin) pluginClass.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(String.format("can't create '%s's instance: %s", pluginClassName, e.getMessage()), e);
        }
    }

    private void pathNodeTypeRepeatCheck(List<PathEntity> treePathEntities) throws AptProcessException {
        Map<String, PathEntity> repeatCheckContainer = new HashMap<>();
        for (PathEntity pathEntity : treePathEntities) {
            for (PathEntity.Node node : pathEntity.nodes) {
                PathEntity repeatPathEntity = repeatCheckContainer.put(node.type, pathEntity);
                if (repeatPathEntity != null) {
                    throw new AptProcessException(
                            String.format(
                                    "found repeat root node type '%s' on %s and %s, please check root type or path's link relations",
                                    node.type,
                                    repeatPathEntity.host.getQualifiedName(),
                                    pathEntity.host.getQualifiedName()
                            ),
                            pathEntity.host
                    );
                }
            }
        }
    }

    private void checkPathAptGlobalConfig(RoundEnvironment roundEnv) throws AptProcessException {
        final Set<TypeElement> annotationElements = ElementFilter.typesIn(
                roundEnv.getElementsAnnotatedWith(PathAptGlobalConfig.class)
        );

        if (annotationElements.isEmpty()) {
            return;
        }

        boolean isRepeat = pathAptGlobalConfig != null || annotationElements.size() > 1;

        if (isRepeat) {

            final LinkedList<TypeElement> repeatElements = new LinkedList<>(annotationElements);
            if (lastPathAptGlobalConfigAnnotationHost != null) {
                repeatElements.add(lastPathAptGlobalConfigAnnotationHost);
            }

            final Iterator<TypeElement> elementIterator = repeatElements.iterator();
            final StringBuilder elementsTip = new StringBuilder();
            while (elementIterator.hasNext()) {
                elementsTip.append(
                        elementIterator.next().getQualifiedName().toString()
                );
                if (elementIterator.hasNext()) {
                    elementsTip.append(",").append(' ');
                }
            }

            throw new AptProcessException(
                    String.format(
                            "%s annotation only can exist one, but found more: %s",
                            PathAptGlobalConfig.class.getSimpleName(), elementsTip
                    ),
                    repeatElements.get(0)
            );
        }

        final TypeElement host = annotationElements.iterator().next();
        final PathAptGlobalConfig config = host.getAnnotation(PathAptGlobalConfig.class);

        AnnotationMirror configMirror = null;
        for (AnnotationMirror annotationMirror : host.getAnnotationMirrors()) {
            if (annotationMirror.getAnnotationType().equals(processingEnv.getElementUtils().getTypeElement(PathAptGlobalConfig.class.getCanonicalName()).asType())) {
                configMirror = annotationMirror;
                break;
            }
        }
        assert configMirror != null;

        lastPathAptGlobalConfigAnnotationHost = host;
        pathAptGlobalConfigMirror = configMirror;
        pathAptGlobalConfig = config;
    }

    private void showErrorTip(AptProcessException e) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        e.printStackTrace(new PrintStream(os));
        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                os.toString(),
                e.e, e.a, e.v
        );
    }
}
