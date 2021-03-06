/*
 * Copyright 2011 Axis Data Management Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.admc.jcreole;

import java.util.EnumSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.IOException;
import java.io.File;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.admc.util.IOUtil;
import com.admc.util.Expander;

/**
 * Generates HTML fragments from supplied Creole wikitext, optionally making a
 * complete HTML page by merging the generated fragment with a HTML page
 * boilerplate.
 * <p>
 * Assembles HTML pages built around main content built from Creole wikitext.
 * </p><p>
 * Applications may use CreoleScanner and CreoleParser directly for more precise
 * control over how HTML pages are constructed, but be aware that the
 * parser and scanner levels generate only HTML fragments.
 * The easiest method of customizing behavior for an app would be to use the
 * methods of this class (but not the main() method, which is intended for
 * convience as opposed to flexibility).
 * For heavier customization you could subclass this class, or start with a
 * copy of the source code and modify it to fit with your application design
 * and technologies.
 * </p><p>
 * This class manages 3 different Expanders.
 * </p><ol>
 * <li>framingExpander:  Merges the main page components boilerplate +
 *     pageHeaders + pageContent (by default via postProcess method)
 *     to make a complete HTML page.
 *     Expands a boilerplate.  Map values are HTML.
 * <li>creoleExpander:  Expands creole text (before parsing).
 *     Map values are Creole.  (Defaults to null).
 * <li>htmlExpander:  Expands post-generated HTML page.  Map values are HTML.
 *     Defaults to empty map unless you use the main method here, in which
 *     case you get the several mappings listed in the JavaDoc for the main
 *     method.
 * </ol><p>
 * As a consequence of what the expanders expand, if using default behavior,
 * boilerplate text should !-reference 'pageHeaders' and 'pageContent' but
 * nothing else.
 * (Unless you are pre-processing the boilerplate external to JCreole).
 * Creole may !-reference anywhere, since the earlier passes are expanded with
 * the ignoreBang option.  A Creole text !-reference means that they reference
 * must be satisfied with either the creoleExpander or the htmlExpander and the
 * Creole author doesn't need to be concerned with which of the two.
 * </p>
 *
 * @see CreoleScanner
 * @see CreoleParser
 * @author Blaine Simpson (blaine dot simpson at admc dot com)
 * @since 1.0
 */
public class JCreole {
    private static Log log = LogFactory.getLog(JCreole.class);

    private static final String DEFAULT_BP_RES_PATH = "boilerplate-inet.html";
    
    private static SimpleDateFormat isoDateTimeFormatter =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
    private static SimpleDateFormat isoDateFormatter =
            new SimpleDateFormat("yyyy-MM-dd");

    public static final String SYNTAX_MSG =
        "java -jar .../jcreole-*.jar [-d] [-] [-r /classpath/boiler.html] "
        + "[-f fs/boiler.html] [-o fspath/out.html] pathto/input.creole\n\n"
        + "The -, -r, and -f options are mutually exclusive.\n"
        + "  NONE:      Default built-in boilerplate (\""
        +    DEFAULT_BP_RES_PATH + "\").\n"
        + "  -:         No boilerplate.  Output will be just a HTML fragment.\n"
        + "  -r path:   Load specified boilerplate file from Classpath.\n"
        + "             Example:  -r /boilerplate-standalone.html\n"
        + "  -f path:   Load specified boilerplate file from file system.\n"
        + "  -d option: Loads an IntraWiki-link debug mapper and a sample\n"
        + "             creoleMapper from 'testMacro'.\n"
        + "  -t:        Troubleshoot failing Creole input.\n"
        + "             This does not output HTML but reports on likely error "
        +               "location.  Best effort that doesn't work with some "
        +               "paired tags.\n"
        + "If either -r or -f is specified, the specified boilerplate should "
        + "include\n'$(pageContent)' at the point(s) where you want content "
        + "generated from your Creole\ninserted.\n"
        + "If the outputfile already exists, it will be silently "
        + "overwritten.\n"
        + "The input Creole file is sought first in the classpath "
        + "(relative to classpath\n"
        + "roots) then falls back to looking for a filesystem file.\n"
        + "Output is always written with UTF-8 encoding.";

    protected CreoleParser parser = new CreoleParser();
    private CharSequence pageBoilerPlate;
    private Expander creoleExpander;
    private Expander htmlExpander = new Expander(Expander.PairedDelims.CURLY);
    private List<String> cssHrefs;
    private Expander framingExpander =
            new Expander(Expander.PairedDelims.ROUNDED);

    /**
     * Returns reference to the Framing Expander.
     * Will have no effect if no boilerplate is used (e.g. if the postProcess
     * method is not run).
     */
    public Expander getFramingExpander() {
        return framingExpander;
    }

    /**
     * Returns reference to the HTML Expander.
     * By default the expander will have no mappings.
     * It should never be null because if no HTML expansions are desired we
     * will need to run expand() on the final HTML to enforce ! references.
     */
    public Expander getHtmlExpander() {
        return htmlExpander;
    }

    /**
     * Run this method with no parameters to see syntax requirements and the
     * available parameters.
     *
     * N.b. do not call this method from a persistent program, because it
     * may call System.exit!
     * <p>
     * Any long-running program should use the lower-level methods in this
     * class instead (or directly use CreoleParser and CreoleScanner
     * instances).
     * </p> <p>
     * This method executes with all JCreole privileges.
     * </p> <p>
     * This method sets up the following htmlExpander mappings (therefore you
     * can reference these in both Creole and boilerplate text).<p>
     * <ul>
     *   <li>sys|*: mappings for Java system properties
     *   <li>isoDateTime
     *   <li>isoDate
     *   <li>pageTitle: Value derived from the specified Creole file name.
     * </ul>
     *
     * @throws IOException for any I/O problem that makes it impossible to
     *         satisfy the request.
     * @throws CreoleParseException
     *         if can not generate output, or if the run generates 0 output.
     *         If the problem is due to input formatting, in most cases you
     *         will get a CreoleParseException, which is a RuntimException, and
     *         CreoleParseException has getters for locations in the source
     *         data (though they will be offset for \r's in the provided
     *         Creole source, if any).
     */
    public static void main(String[] sa) throws IOException {
        String bpResPath = null;
        String bpFsPath = null;
        String outPath = null;
        String inPath = null;
        boolean debugs = false;
        boolean troubleshoot = false;
        boolean noBp = false;
        int param = -1;
        try {
            while (++param < sa.length) {
                if (sa[param].equals("-d")) {
                    debugs = true;
                    continue;
                }
                if (sa[param].equals("-t")) {
                    troubleshoot = true;
                    continue;
                }
                if (sa[param].equals("-r") && param + 1 < sa.length) {
                    if (bpFsPath != null || bpResPath != null || noBp)
                            throw new IllegalArgumentException();
                    bpResPath = sa[++param];
                    continue;
                }
                if (sa[param].equals("-f") && param + 1 < sa.length) {
                    if (bpResPath != null || bpFsPath != null || noBp)
                            throw new IllegalArgumentException();
                    bpFsPath = sa[++param];
                    continue;
                }
                if (sa[param].equals("-")) {
                    if (noBp || bpFsPath != null || bpResPath != null)
                            throw new IllegalArgumentException();
                    noBp = true;
                    continue;
                }
                if (sa[param].equals("-o") && param + 1 < sa.length) {
                    if (outPath != null) throw new IllegalArgumentException();
                    outPath = sa[++param];
                    continue;
                }
                if (inPath != null) throw new IllegalArgumentException();
                inPath = sa[param];
            }
            if (inPath == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException iae) {
            System.err.println(SYNTAX_MSG);
            System.exit(1);
        }
        if (!noBp && bpResPath == null && bpFsPath == null)
            bpResPath = DEFAULT_BP_RES_PATH;
        String rawBoilerPlate = null;
        if (bpResPath != null) {
            if (bpResPath.length() > 0 && bpResPath.charAt(0) == '/')
                // Classloader lookups are ALWAYS relative to CLASSPATH roots,
                // and will abort if you specify a beginning "/".
                bpResPath = bpResPath.substring(1);
            InputStream iStream = Thread.currentThread()
                    .getContextClassLoader().getResourceAsStream(bpResPath);
            if (iStream == null)
                throw new IOException("Boilerplate inaccessible: " + bpResPath);
            rawBoilerPlate = IOUtil.toString(iStream);
        } else if (bpFsPath != null) {
            rawBoilerPlate = IOUtil.toString(new File(bpFsPath));
        }
        String creoleResPath =
                (inPath.length() > 0 && inPath.charAt(0) == '/')
                ? inPath.substring(1)
                : inPath;
            // Classloader lookups are ALWAYS relative to CLASSPATH roots,
            // and will abort if you specify a beginning "/".
        InputStream creoleStream = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream(creoleResPath);
        File inFile = (creoleStream == null) ? new File(inPath) : null;
        JCreole jCreole = (rawBoilerPlate == null)
                ? (new JCreole()) : (new JCreole(rawBoilerPlate));
        if (debugs) {
            jCreole.setInterWikiMapper(new InterWikiMapper() {
                // This InterWikiMapper is just for prototyping.
                // Use wiki name of "Nil" to force lookup failure for path.
                // Remember that wiki names must contain a non-lowercase-letter
                // to distinguish them from URL protocols.
                public String toPath(String wikiName, String wikiPage) {
                    if (wikiName != null && wikiName.equals("Nil")) return null;
                    return "{WIKI-LINK to: " + wikiName + '|' + wikiPage + '}';
                }
                // Use page name of "nil" to force lookup failure for path.
                public String toLabel(String wikiName, String wikiPage) {
                    if (wikiPage == null)
                            throw new RuntimeException(
                                    "Null page name sent to InterWikiMapper");
                    if (wikiPage.equals("nil")) return null;
                    return "{LABEL for: " + wikiName + '|' + wikiPage + '}';
                }
            });
            Expander creoleExpander =
                    new Expander(Expander.PairedDelims.RECTANGULAR);
            creoleExpander.put("testMacro", "\n\n<<prettyPrint>>\n{{{\n"
                    + "!/bin/bash -p\n\ncp /etc/inittab /tmp\n}}}\n");
            jCreole.setCreoleExpander(creoleExpander);
        }
        jCreole.setPrivileges(EnumSet.allOf(JCreolePrivilege.class));
        Expander exp = jCreole.getHtmlExpander();
        Date now = new Date();
        exp.putAll("sys", System.getProperties(), false);
        exp.put("isoDateTime", isoDateTimeFormatter.format(now), false);
        exp.put("isoDate", isoDateFormatter.format(now), false);
        exp.put("pageTitle", (inFile == null)
                ? creoleResPath.replaceFirst("[.][^.]*$", "")
                    .replaceFirst(".*[/\\\\.]", "")
                : inFile.getName().replaceFirst("[.][^.]*$", ""));
        if (troubleshoot) {
            // We don't write any HMTL output here.
            // Goal is just to output diagnostics.
            StringBuilder builder = (creoleStream == null)
                    ? IOUtil.toStringBuilder(inFile)
                    : IOUtil.toStringBuilder(creoleStream);
            int newlineCount = 0;
            int lastOffset = -1;
            int offset = builder.indexOf("\n");
            for (; offset >= 0; offset = builder.indexOf("\n", offset + 1)) {
                lastOffset = offset;
                newlineCount++;
            }
            // Accommodate input files with no terminating newline:
            if (builder.length() > lastOffset + 1) newlineCount++;
            System.out.println("Input lines:  " + newlineCount);
            Exception lastException = null;
            while (true) {
                try {
                    jCreole.parseCreole(builder);
                    break;
                } catch (Exception e) {
                    lastException = e;
                }
                if (builder.charAt(builder.length() - 1) == '\n')
                    builder.setLength(builder.length() - 1);
                offset = builder.lastIndexOf("\n");
                if (offset < 1) break;
                newlineCount--;
                builder.setLength(builder.lastIndexOf("\n"));
            }
            System.out.println((lastException == null)
                    ? "Input validates"
                    : String.format("Error around input line %d:  %s",
                              newlineCount, lastException.getMessage()));
            return;
        }
        String generatedHtml = (creoleStream == null)
                ? jCreole.parseCreole(inFile)
                : jCreole.parseCreole(IOUtil.toStringBuilder(creoleStream));
        String html = jCreole.postProcess(
                generatedHtml, SystemUtils.LINE_SEPARATOR);
        if (outPath == null) {
            System.out.print(html);
        } else {
            FileUtils.writeStringToFile(new File(outPath), html, "UTF-8");
        }
    }

    /**
     * Use this when you want to work with HTML fragments externally.
     * Use the JCreole(String) constructor instead if you want JCreole to
     * manage HTML page construction with a Boilerplate.
     */
    public JCreole() {
        // Intentionally empty
    }

    public JCreole(String rawBoilerPlate) {
        if (rawBoilerPlate.indexOf("$(pageContent)") < 0
                && rawBoilerPlate.indexOf("$(!pageContent)") < 0)
            throw new IllegalArgumentException("Boilerplate contains "
                    + "neither $(pageContent) nor $(!pageContent)");
        pageBoilerPlate = rawBoilerPlate.replace("\r", "");
    }

    /**
     * Returns a HTML <strong>FRAGMENT</strong> from the specified Creole
     * Wikitext.
     * You don't need to worry about \r's in input, as they will automatically
     * be stripped if present.
     * (We will, however, throw if binary characters are detected in input).
     *
     * @throws CreoleParseException
     *         if can not generate output, or if the run generates 0 output.
     *         If the problem is due to input formatting, in most cases you
     *         will get a CreoleParseException, which is a RuntimException, and
     *         CreoleParseException has getters for locations in the source
     *         data (though they will be offset for \r's in the provided
     *         Creole source, if any).
     */
    public String parseCreole(StringBuilder sb) throws IOException {
        if (sb == null || sb.length() < 1)
            throw new IllegalArgumentException("No input supplied");
        CreoleScanner scanner =
                CreoleScanner.newCreoleScanner(sb, true, creoleExpander);
        // using a named instance so we can enhance this to set scanner
        // instance properties.
        Object retVal = null;
        try {
            retVal = parser.parse(scanner);
        } catch (CreoleParseException cpe) {
            throw cpe;
        } catch (beaver.Parser.Exception bpe) {
            throw new CreoleParseException(bpe);
        } catch (RuntimeException rte) {
            log.error("Unexpected problem.  Passing RuntimeException to caller",
                    rte);
            throw new CreoleParseException("Unexpected problem", rte);
        }
        if (!(retVal instanceof WashedSymbol)) {
            log.error("Parser returned unexpected type "
                    + retVal.getClass().getName() + ".  Throwing RTE.");
            throw new IllegalStateException(
                    "Parser returned unexpected type: "
                    + retVal.getClass().getName());
        }
        return ((WashedSymbol) retVal).toString();
    }

    /**
     * Returns a HTML <strong>FRAGMENT</strong> from the specified Creole
     * Wikitext file.
     * You don't need to worry about \r's in input, as they will automatically
     * be stripped if present.
     * (The will, however, throw if binary characters are detected in input).
     *
     * @throws if can not generate output, or if the run generates 0 output.
     *         If the problem is due to input formatting, in most cases you
     *         will get a CreoleParseException, which is a RuntimException, and
     *         CreoleParseException has getters for locations in the source
     *         data (though they will be offset for \r's in the provided
     *         Creole source, if any).
     */
    public String parseCreole(File creoleFile) throws IOException {
        if (creoleFile == null || creoleFile.length() < 1)
            throw new IllegalArgumentException("No input supplied");
        CreoleScanner scanner = CreoleScanner.newCreoleScanner(
                creoleFile, false, creoleExpander);
        // using a named instance so we can enhance this to set scanner
        // instance properties.
        Object retVal = null;
        try {
            retVal = parser.parse(scanner);
        } catch (CreoleParseException cpe) {
            throw cpe;
        } catch (beaver.Parser.Exception bpe) {
            throw new CreoleParseException(bpe);
        }
        if (!(retVal instanceof WashedSymbol))
            throw new IllegalStateException(
                    "Parser returned unexpected type: "
                    + retVal.getClass().getName());
        return ((WashedSymbol) retVal).toString();
    }

    /**
     * Generates clean html-expanded HTML with specified EOL type.
     * If 'pageBoilerPlate' is set for this JCreole instance, then will return
     * an html-expanded, eol-converted merge of the page boilerplate with
     * embedded HTML fragment.
     * Otherwise will just return the supplied fragment with the Eols converted
     * as necessary and html variable expansion.
     * <p>
     * Call like <PRE>
     *    postProcess(htmlFrag, System.getProperty("line.separator"));
     * to have output match your local platform default.
     * </p> <p>
     * Input htmlFrag doesn't need to worry about line delimiters, because it
     * will be cleaned up as required.
     * </p> <p>
     * If you are using no boilerplate and want \n line delimiters in output,
     * then it is more efficient to just call htmlExpand() instead of this
     * method.
     * </p> <p>
     * This method implementation has dependencies on the provide boilerplates
     * or others that follow its conventions.
     * </p>
     *
     * @param outputEol  Line delimiters for output.  Null to leave as \n's.
     * @throws if can not generate output, or if the run generates 0 output.
     *         If the problem is due to input formatting, in most cases you
     *         will get a CreoleParseException, which is a RuntimException, and
     *         CreoleParseException has getters for locations in the source
     *         data (though they will be offset for \r's in the provided
     *         Creole source, if any).
     */
    public String postProcess(String htmlFrag, String outputEol)
            throws IOException {
        String htmlString = null;
        if (pageBoilerPlate == null) {
            htmlString = htmlFrag;
        } else {
            StringBuilder html = new StringBuilder(pageBoilerPlate);
            if (html.indexOf("$(pageHeaders)") > -1
                    || html.indexOf("$(!pageHeaders)") > -1) {
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (String href : getCssHrefs())
                    sb.append(String.format(
                            "<link id=\"auto%02d\" class=\"auto\" "
                            + "rel=\"stylesheet\" "
                            + "type=\"text/css\" href=\"%s\" />\n",
                            ++count, href));
                if (getDefaultTargetWindow() != null)
                    sb.append(String.format("<base target=\"%s\">\n",
                      getDefaultTargetWindow()));
                framingExpander.put("pageHeaders", sb.toString(), false);
            } else if (getCssHrefs().size() > 0
              || getDefaultTargetWindow() != null) {
                throw new CreoleParseException(
                  "Author-supplied style-sheets or default target window, "
                  + "but boilerplate has no 'pageHeaders' insertion-point");
            }
            framingExpander.put("pageContent", htmlFrag, false);
            htmlString = framingExpander.expand(html).toString();
        }
        if (outputEol != null && !outputEol.equals("\n"))
            htmlString = htmlString.replace("\n", outputEol);
            // Amazing that StringBuilder can't do a multi-replace like this
        return htmlExpand(htmlString);
    }

    public String htmlExpand(String htmlString) {
        return htmlExpander.expandToString(htmlString);
    }

    /**
     * Will use the specified creoleExpander when instantiating the scanner.
     *
     * @see CreoleScanner#newCreoleScanner(File, boolean, Expander)
     * @see CreoleScanner#newCreoleScanner(StringBuilder, boolean, Expander)
     */
    public void setCreoleExpander(Expander creoleExpander) {
        this.creoleExpander = creoleExpander;
    }

    /**
     * Calls the corresponding method on the underlying Parser.
     *
     * @see CreoleParser#setPrivileges(EnumSet)
     */
    public void setPrivileges(EnumSet<JCreolePrivilege> jcreolePrivs) {
        parser.setPrivileges(jcreolePrivs);
    }

    /**
     * Legacy wrapper
     */
    public void setEnumerationFormats(String enumerationFormats) {
        parser.setEnumSymbols(enumerationFormats, true);
    }

    /**
     * Calls the corresponding method on the underlying Parser.
     *
     * @param forSection
     *        If true apply to sections; if false apply to ordered lists.
     * @see CreoleParser#setEnumSymbols(String, boolean)
     */
    public void setEnumSymbols(String symbolString, boolean forSection) {
        parser.setEnumSymbols(symbolString, forSection);
    }

    /**
     * Calls the corresponding method on the underlying Parser.
     *
     * @see CreoleParser#getPrivileges()
     */
    public EnumSet<JCreolePrivilege> getPrivileges() {
        return parser.getPrivileges();
    }

    /**
     * Calls the corresponding method on the underlying Parser.
     *
     * @see CreoleParser#setInterWikiMapper(InterWikiMapper)
     */
    public void setInterWikiMapper(InterWikiMapper interWikiMapper) {
        parser.setInterWikiMapper(interWikiMapper);
    }

    /**
     * Calls the corresponding method on the underlying Parser.
     *
     * @see CreoleParser#getDefaultTargetWindow
     */
    public String getDefaultTargetWindow() {
        return parser.getDefaultTargetWindow();
    }

    /**
     * Gets the underlying Parser, with which you can do a lot of useful stuff.
     *
     * @see CreoleParser
     */
    public CreoleParser getParser() {
        return parser;
    }

    /**
     * Returns a new list consisting of explicitly set cssHrefs + cssHrefs of
     * the underlying Parser.
     *
     * @see CreoleParser#getCssHrefs
     */
    public List<String> getCssHrefs() {
        List<String> outCssHrefs = (cssHrefs == null)
                ? (new ArrayList<String>()) : (new ArrayList<String>(cssHrefs));
        outCssHrefs.addAll(parser.getCssHrefs());
        return outCssHrefs;
    }

    public void addCssHrefs(List<String> newCssHrefs) {
        // Used by servlets
        cssHrefs = new ArrayList<String>(newCssHrefs);
    }

    /**
     * Beware of this method.  It will wipe the provided htmlExpander!
     * This method is useful for sharing an Expander created elsewhere.
     */
    public void setHtmlExpander(Expander htmlExpander) {
        this.htmlExpander = htmlExpander;
    }
}
