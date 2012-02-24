
package no.priv.garshol.duke;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Collection;
import java.io.IOException;
import java.io.Writer;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;

import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import org.apache.lucene.index.CorruptIndexException;

import no.priv.garshol.duke.matchers.AbstractMatchListener;
import no.priv.garshol.duke.matchers.TestFileListener;
import no.priv.garshol.duke.matchers.PrintMatchListener;
import no.priv.garshol.duke.utils.NTriplesWriter;
import no.priv.garshol.duke.utils.CommandLineParser;

/**
 * Command-line interface to the engine.
 */
public class Duke {
  private static Properties properties;

  public static void main(String[] argv)
    throws IOException, CorruptIndexException {
    try {
      main_(argv);
    } catch (DukeConfigException e) {
      System.err.println("ERROR: " + e.getMessage());
    }
  }

  public static void main_(String[] argv)
    throws IOException, CorruptIndexException {
    
    CommandLineParser parser = new CommandLineParser();
    parser.setMinimumArguments(1);
    parser.registerOption(new CommandLineParser.BooleanOption("progress", 'p'));
    parser.registerOption(new CommandLineParser.StringOption("linkfile", 'l'));
    parser.registerOption(new CommandLineParser.StringOption("linkendpoint", 'e'));
    parser.registerOption(new CommandLineParser.BooleanOption("showmatches", 's'));
    parser.registerOption(new CommandLineParser.BooleanOption("showmaybe", 'm'));
    parser.registerOption(new CommandLineParser.StringOption("testfile", 'T'));
    parser.registerOption(new CommandLineParser.BooleanOption("testdebug", 't'));
    parser.registerOption(new CommandLineParser.StringOption("batchsize", 'b'));
    parser.registerOption(new CommandLineParser.BooleanOption("verbose", 'v'));
    parser.registerOption(new CommandLineParser.StringOption("threads", 'P'));

    try {
      argv = parser.parse(argv);
    } catch (CommandLineParser.CommandLineParserException e) {
      System.out.println("ERROR: " + e.getMessage());
      usage();
      System.exit(1);
    }

    Logger logger = new CommandLineLogger(parser.getOptionState("verbose") ?
                                          1 : 0);
    boolean progress = parser.getOptionState("progress");
    int count = 0;
    int batch_size = 40000;
    if (parser.getOptionValue("batchsize") != null)
      batch_size = Integer.parseInt(parser.getOptionValue("batchsize"));
    
    Configuration config;
    try {
      config = ConfigLoader.load(argv[0]);
    } catch (FileNotFoundException e) {
      System.err.println("ERROR: Config file '" + argv[0] + "' not found!");
      return;
    } catch (SAXParseException e) {
      System.err.println("ERROR: Couldn't parse config file: " + e.getMessage());
      System.err.println("Error in " + e.getSystemId() + ":" +
                         e.getLineNumber() + ":" + e.getColumnNumber());
      return;
    } catch (SAXException e) {
      System.err.println("ERROR: Couldn't parse config file: " + e.getMessage());
      return;
    }

    Processor processor;
    if (parser.getOptionValue("threads") == null)
      processor = new Processor(config);
    else {
      processor = new MultithreadProcessor2(config);
      ((MultithreadProcessor2) processor).setThreadCount(Integer.parseInt(parser.getOptionValue("threads")));
    }
    processor.setLogger(logger);
    PrintMatchListener listener =
      new PrintMatchListener(parser.getOptionState("showmatches"),
                             parser.getOptionState("showmaybe"),
                             progress);
    processor.addMatchListener(listener);

    AbstractLinkFileListener linkfile = null;
    if (parser.getOptionValue("linkfile") != null) {
      String fname = parser.getOptionValue("linkfile");
      if (fname.endsWith(".ntriples"))
        linkfile = new NTriplesLinkFileListener(fname, config.getIdentityProperties());
      else
        linkfile = new LinkFileListener(fname, config.getIdentityProperties());
      processor.addMatchListener(linkfile);
    }
    
    TestFileListener testfile = null;
    if (parser.getOptionValue("testfile") != null) {
      testfile = new TestFileListener(parser.getOptionValue("testfile"),
                                      config.getIdentityProperties(),
                                      parser.getOptionState("testdebug"),
                                      processor);
      processor.addMatchListener(testfile);
    }

    // this is where the two modes separate.
    if (!config.getDataSources().isEmpty())
      // deduplication mode
      processor.deduplicate(config.getDataSources(), batch_size);
    else
      // record linkage mode
      processor.link(config.getDataSources(1),
                     config.getDataSources(2),
                     batch_size);

    if (parser.getOptionValue("linkfile") != null)
      linkfile.close();
    if (parser.getOptionValue("testfile") != null)
      testfile.close();
    processor.close();
  }
  
  private static void usage() throws IOException {
    System.out.println("");
    System.out.println("java no.priv.garshol.duke.Duke [options] <cfgfile>");
    System.out.println("");
    System.out.println("  --progress            show progress report while running");
    System.out.println("  --showmatches         show matches while running");
    System.out.println("  --linkfile=<file>     output matches to link file");
    System.out.println("  --testfile=<file>     output accuracy stats");
    System.out.println("  --testdebug           display failures");
    System.out.println("  --verbose             display diagnostics");
    System.out.println("");
    System.out.println("Duke version " + getVersionString());
  }

  public static String getVersionString() throws IOException {
    Properties props = getProperties();
    return props.getProperty("duke.version") + ", build " +
           props.getProperty("duke.build") + ", built by " +
           props.getProperty("duke.builder");
  }
  
  private static Properties getProperties() throws IOException {
    if (properties == null) {
      properties = new Properties();
      InputStream in = Duke.class.getClassLoader().getResourceAsStream("no/priv/garshol/duke/duke.properties");
      properties.load(in);
      in.close();
    }
    return properties;
  }
  
  static abstract class AbstractLinkFileListener extends AbstractMatchListener {
    private Collection<Property> idprops;
    
    public AbstractLinkFileListener(Collection<Property> idprops) {
      this.idprops = idprops;
    }

    public void close() throws IOException {
    }

    public abstract void link(String id1, String id2) throws IOException;
    
    public void matches(Record r1, Record r2, double confidence) {
      try {
        for (Property p : idprops)
          for (String id1 : r1.getValues(p.getName()))
            for (String id2 : r2.getValues(p.getName()))
              link(id1, id2);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  static class LinkFileListener extends AbstractLinkFileListener {
    private Writer out;
    
    public LinkFileListener(String linkfile, Collection<Property> idprops)
      throws IOException {
      super(idprops);
      this.out = new FileWriter(linkfile);
    }
    
    public void link(String id1, String id2) throws IOException {
      out.write(id1 + "," + id2 + "\n");
    }
    
    public void close() throws IOException {
      out.close();
    }
  }

  static class NTriplesLinkFileListener extends AbstractLinkFileListener {
    private FileOutputStream fos;
    private NTriplesWriter out;
    
    public NTriplesLinkFileListener(String linkfile,
                                    Collection<Property> idprops)
      throws IOException {
      super(idprops);
      this.fos = new FileOutputStream(linkfile);
      this.out = new NTriplesWriter(fos);
    }
    
    public void link(String id1, String id2) throws IOException {
      out.statement(id1, "http://www.w3.org/2002/07/owl#sameAs", id2, false);
    }
    
    public void close() throws IOException {
      out.done();
      fos.close();
    }
  }

  static class CommandLineLogger implements Logger {
    private int loglevel; // 1: trace, 2: debug, 3: info, 4: warn, 5: error

    private CommandLineLogger(int loglevel) {
      this.loglevel = loglevel;
    }

    public void trace(String msg) {
      if (isTraceEnabled())
        System.out.println(msg);
    }
    
    public void debug(String msg) {
      if (isDebugEnabled())
        System.out.println(msg);
    }

    public void info(String msg) {
      if (isInfoEnabled())
        System.out.println(msg);
    }

    public void error(String msg) {
      error(msg, null);
    }

    public void error(String msg, Throwable e) {
      if (!isErrorEnabled())
        return;

      System.out.println(msg + " " + e);
      e.printStackTrace();
    }
    
    public boolean isTraceEnabled() {
      return loglevel == 1;
    }

    public boolean isDebugEnabled() {
      return loglevel != 0 && loglevel < 3;
    }

    public boolean isInfoEnabled() {
      return loglevel != 0 && loglevel < 4;
    }

    public boolean isErrorEnabled() {
      return loglevel != 0 && loglevel < 6;
    }
  }
}