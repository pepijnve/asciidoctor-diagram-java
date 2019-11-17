package org.asciidoctor.diagram.plantuml;

import net.sourceforge.plantuml.*;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.cucadiagram.dot.GraphvizUtils;
import net.sourceforge.plantuml.error.PSystemError;
import net.sourceforge.plantuml.preproc.Defines;
import org.asciidoctor.diagram.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

public class PlantUML implements DiagramGenerator
{
    public static final MimeType DEFAULT_OUTPUT_FORMAT = MimeType.PNG;

    private static Method SET_DOT_EXE;
    private static Object SET_DOT_EXE_INSTANCE;

    static {
        try {
            SET_DOT_EXE = OptionFlags.class.getMethod("setDotExecutable", String.class);
            SET_DOT_EXE_INSTANCE = OptionFlags.getInstance();
        } catch (NoSuchMethodException e) {
            // Try next option
        }

        try {
            SET_DOT_EXE = GraphvizUtils.class.getMethod("setDotExecutable", String.class);
            SET_DOT_EXE_INSTANCE = null;
        } catch (NoSuchMethodException e) {
            // Try next option
        }

        if (SET_DOT_EXE == null) {
            throw new RuntimeException("Could not find setDotExecutable method");
        }
    }

    @Override
    public String getName()
    {
        return "plantuml";
    }

    @Override
    public ResponseData generate(Request request) throws IOException {
        File graphviz;

        String pathToGraphViz = request.headers.getValue("X-Graphviz");
        if (pathToGraphViz != null) {
            File graphvizParam = new File(pathToGraphViz);
            if (graphvizParam.canExecute()) {
                graphviz = graphvizParam;
            } else {
                throw new IOException("GraphViz 'dot' tool at '" + pathToGraphViz + "' is not executable");
            }
        } else {
            graphviz = null;
        }

        MimeType format = request.headers.getValue(HTTPHeader.ACCEPT);

        if (format == null) {
            format = DEFAULT_OUTPUT_FORMAT;
        }

        FileFormatOption fileFormat;
        if (format.equals(MimeType.PNG)) {
            fileFormat = new FileFormatOption(FileFormat.PNG);
        } else if (format.equals(MimeType.SVG)) {
            fileFormat = new FileFormatOption(FileFormat.SVG);
        } else if (format.equals(MimeType.TEXT_PLAIN_UTF8)) {
            fileFormat = new FileFormatOption(FileFormat.UTXT);
        } else if (format.equals(MimeType.TEXT_PLAIN_ASCII)) {
            fileFormat = new FileFormatOption(FileFormat.ATXT);
        } else {
            throw new IOException("Unsupported output format: " + format);
        }

        Option option = new Option();

        String plantUmlConfig = request.headers.getValue("X-PlantUML-Config");
        if (plantUmlConfig != null) {
            option.initConfig(plantUmlConfig);
        }
        option.setFileFormatOption(fileFormat);
        option.setFailfast(true);
        option.setFailfast2(true);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        synchronized (this) {
            try {
                SET_DOT_EXE.invoke(SET_DOT_EXE_INSTANCE, graphviz != null ? graphviz.getAbsolutePath() : null);
            } catch (IllegalAccessException e) {
                throw new IOException(e);
            } catch (InvocationTargetException e) {
                throw new IOException(e);
            }

            BlockUmlBuilder builder = new BlockUmlBuilder(
                    option.getConfig(),
                    "UTF-8",
                    Defines.createEmpty(),
                    new StringReader(request.asString()),
                    FileSystem.getInstance().getCurrentDir(),
                    "string"
            );

            List<BlockUml> blocks = builder.getBlockUmls();

            if (blocks.size() == 0) {
                throw new IOException("No @startuml found");
            } else {
                int nbInSystem;
                int numImage = 0;
                for (BlockUml block : blocks) {
                    Diagram system = block.getDiagram();
                    if (system instanceof PSystemError) {
                        throw new IOException(((PSystemError) system).getWarningOrError());
                    }
                    nbInSystem = system.getNbImages();
                    if (numImage < nbInSystem) {
                        system.exportDiagram(byteArrayOutputStream, numImage, fileFormat);
                    };
                    numImage -= nbInSystem;
                }
            }
        }

        return new ResponseData(
                format,
                byteArrayOutputStream.toByteArray()
        );

    }
}
