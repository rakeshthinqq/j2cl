/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.frontend;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.io.Files;
import com.google.j2cl.common.J2clUtils;
import com.google.j2cl.errors.Problems;
import com.google.j2cl.errors.Problems.Messages;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionHandlerFilter;

/** The set of supported flags. */
public class FrontendFlags {
  @Argument(metaVar = "<source files .java|.srcjar>", usage = "source files")
  protected List<String> files = new ArrayList<>();

  @Option(
    name = "-classpath",
    aliases = {"-cp"},
    metaVar = "<path>",
    usage = "Specifies where to find user class files and annotation processors."
  )
  protected String classpath = "";

  @Option(
    name = "-sourcepath",
    metaVar = "<file>",
    usage = "Specifies where to find input source files."
  )
  protected String sourcepath = "";

  @Option(
    name = "-nativesourcezip",
    metaVar = "<file>",
    usage = "Specifies where to find zip file containing js impl files for native methods."
  )
  protected String nativesourceszippath = "";

  /** Option that allows users to swap out the location of the JRE library. */
  @Option(
    name = "-bootclasspath",
    metaVar = "<path>",
    usage = "Overrides location of bootstrap class files"
  )
  protected String bootclasspath = "";

  @Option(
    name = "-d",
    metaVar = "<file>",
    usage = "Directory or zip into which to place compiled output."
  )
  // TODO: replace with -output instead of -d
  @VisibleForTesting
  public String output = ".";

  @Option(
    name = "-depinfo",
    metaVar = "<file>",
    usage = "Specifies whether and where to generate a dependency information file.",
    hidden = true
  )
  protected String depinfoPath = null;

  @Option(
    name = "-encoding",
    metaVar = "<encoding>",
    usage = "Specifies character encoding used by source files."
  )
  protected String encoding = System.getProperty("file.encoding", "UTF-8");

  @Option(
    name = "-source",
    metaVar = "<release>",
    usage = "Specifies source compatibility level (1.7, 1.8, etc)."
  )
  protected String source = "1.8";

  @Option(name = "-h", aliases = "-help", usage = "print this message")
  protected boolean help = false;

  @Option(
    name = "-readableSourceMaps",
    usage = "Coerces generated source maps to human readable form.",
    hidden = true
  )
  protected boolean readableSourceMaps = false;

  @Option(
    name = "-declareLegacyNamespace",
    usage =
        "Enable goog.module.declareLegacyNamespace() for generated goog.module()."
            + " For Docs during onboarding, do not use.",
    hidden = true
  )
  protected boolean declareLegacyNamespace = false;

  @Option(
    name = "-time",
    usage = "Generates a report of time spent in all stages of the compiler.",
    hidden = false
  )
  protected boolean generateTimeReport = false;

  private final Problems problems;

  public FrontendFlags(Problems problems) {
    this.problems = problems;
  }

  /** Parses the given args list and updates values. */
  public void parse(String[] args) {
    CmdLineParser parser = new CmdLineParser(this);

    try {
      args = maybeLoadFlagFile(args);
    } catch (IOException e) {
      problems.error(Messages.ERR_FLAG_FILE, e.getMessage());
      return;
    }

    try {
      parser.parseArgument(args);
      if (help) {
        problems.info(J2clUtils.streamToString(parser::printUsage));
        problems.abortWhenPossible();
      }
    } catch (CmdLineException e) {
      String message = e.getMessage() + "\n";
      message += "Valid options: \n" + parser.printExample(OptionHandlerFilter.ALL);
      message += "\nuse -help for a list of possible options in more details";
      problems.error(Messages.ERR_INVALID_FLAG, message);
    }
  }

  private static String[] maybeLoadFlagFile(String[] args) throws IOException {
    // Loads a potential flag file
    // Flag files are only allowed as the last parameter and need to start
    // with an '@'
    if (args.length == 0) {
      return args;
    }

    String potentialFlagFile = args[args.length - 1];

    if (potentialFlagFile == null || !potentialFlagFile.startsWith("@")) {
      return args;
    }

    String flagFile = potentialFlagFile.substring(1);

    List<String> combinedArgs = new ArrayList<>();
    String flagFileContent = Files.toString(new File(flagFile), StandardCharsets.UTF_8);
    List<String> argsFromFlagFile =
        Splitter.on('\n').omitEmptyStrings().splitToList(flagFileContent);
    combinedArgs.addAll(Arrays.asList(args));
    // remove the flag file entry
    combinedArgs.remove(combinedArgs.size() - 1);
    combinedArgs.addAll(argsFromFlagFile);

    return combinedArgs.toArray(new String[0]);
  }
}
