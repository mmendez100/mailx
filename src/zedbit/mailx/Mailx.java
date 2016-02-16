package zedbit.mailx;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Collectors;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.BrowserVersion;

import com.gargoylesoftware.htmlunit.html.DomText;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.DomElement;


/**
 * Mailx implements simple crawling of a website, looking for strings that might be email
 * candidates. The class crawls only resources sharing the same uri, and is capable of
 * "clicking" on dynamic routes (using ng-click). The HtmlUnit headless browser is driven
 * to explore both static and dynamic pages.
 *
 * @author Manuel Mendez
 */
public class Mailx {

    // Member variables
    static WebClient webClient; // The headless browser
    static String startPage; // Where the crawl begins, may be a URI or a page
    static String uri; // The URI 
    static boolean verbose; // Are we running in verbose mode?
    static boolean trace; // Are we running in trace mode? Useful for debugging
    static int linksCrawled; // How many links did we crawl?

    // We will follow two kinds of links, relative which we can build a regex for now, and absolute...
    static final Pattern relHrefP = Pattern.compile("(^.*)(href=\"/)([^\"]+)(.*$)");
    static Pattern absHrefP; // compiled at method processArgs once we have extracted our uri
    static Pattern websiteP; // as above, used to check dynamic links/routes to be within this website

    // Delimiters that we use to separate emails that might exist in the same line
    // Some funky emails including quotes or the like might be split... but the overwhelming
    // majority will be captured, including those with embedded dashes
    static final String DELIMS = "[ ,?!_;:=<>\\(\\)\"]+";

    // Files we will not scan
    static final Pattern skipP = Pattern.compile(".*(\\.(ico|jpg|jpeg|png|xml|php|php\\?rsd|css|pdf|doc|docx)|(/feed/)|(\\.css\\?.*))$", Pattern.CASE_INSENSITIVE);

    // How long of a wait for HtmlUnit to process background JavaScript when reaching a page? (mS)
    static final int JS_WAIT = 50;

    // Some constants for some pretty-printing 
    static final String ANSI_RED = "\u001B[31m";
    static final String ANSI_RESET = "\u001B[0m";
    static final String MARGIN = "        ";

    /**
    * Entry point for Mailx, a simple scrapping sample program for email extraction.
    *
    * arguments contains the commands line.
    * Can have the following form:
    *
    * <p> ...Mailx <an uri to crawl from> [-v|-t], i.e. ...Mailx http://mysite.com -v </p>
    * <p> The Optional argument -v can be provided for verbose output, -t for trace/debug output </p>
    */
    public static void main(String[] arguments) {

        // Verify the command line arguments and load into variables startPage & uri
        processArgs(arguments);

        // Get a headless browser
        setWebClient();

        // Now begin the drill down at startPage!
        crawl(startPage);

        System.out.println ("Crawling completed!\nLinks Crawled: " + linksCrawled 
            + ", " +UrlTracker.summary() );

    } // end main


   
    /**
    * Crawl, but limit ourselves to only links that we have not visited before
    * <code>Crawl(String pageUrl)</code> is called for pages for which we have a static URL.
    * <code>Crawl()</code> is really a function that sets the stage for <code>traverse()</code>
    *  to do the work. 
    */
    private static void crawl(String pageUrl) {

        // Increase crawl count, wait a bit for the page's JavaScript and HtmlUnit to catch up.
        linksCrawled++;
        webClient.waitForBackgroundJavaScript(JS_WAIT);

        // First, check if we have been at this URL before, we back out
        if (UrlTracker.hasBeenVisited(pageUrl) == true) {
            printlnT("We have already visited " + pageUrl + ". Skipping it");
            return;
        }

        // Connect and get the page, backout if we can't.
        printlnV("Crawling Static Link w/URL=" + pageUrl);
        HtmlPage page;  // This will be the page we will be working on, as represented by htmlUnit
        try { page = webClient.getPage(pageUrl); }
        catch (Exception e) {
            UrlTracker.addErrored(pageUrl);
            printlnT("HtmlUnit getPage exception " + e.toString());
            if (trace) { e.printStackTrace(); }
            return;
        }

        // Now do the actual crawling by traversing from the HtmlPage page loaded
        traverse (page, pageUrl);

    } // End crawl

    /**
    * <p> Crawl from the current page dynamically reached by simulating a click. The current
    * page is passed in parameter page. Notice that <code>Crawl(String pageUrl)</code> is 
    * instead called with a String parameter for pages that are expressed via a static URL.</p>
    *
    * <code>Crawl()</code> is really a function that sets the stage for <code>traverse()</code>
    *  to do the work. 
    *
    * Because the URL of a page resulting from a script/route cannot be anticipated until
    * the browser is called, this page might not be inside this website. If this is the case
    * the method backs out of this page. To back out the code simulates pressing the "back"
    * button of the simulated browser.
    * 
    */
    private static void crawl(HtmlPage page) {

        // Increase crawl count, wait a bit for the page's JavaScript and HtmlUnit to catch up.
        linksCrawled++;
        webClient.waitForBackgroundJavaScript(JS_WAIT);

        final String pageUrl = page.getUrl().toString();
        printlnV("Crawling Dynamic Link w/URL=" + pageUrl);

        // Now check if we have been at this URL before, OR if this dynamic
        // link is actually not in this website, if so we back out
        if (UrlTracker.hasBeenVisited(pageUrl) == true) {
            printlnV(MARGIN + pageUrl + " already crawled. Back buttoning it");
            try { webClient.getWebWindows().get(0).getHistory().back(); }
            catch (Exception e) {
                UrlTracker.addErrored(pageUrl); // Could be out of the website
                System.err.println("Back button after dynamic link invocation failed.");
                if (trace) { e.printStackTrace(); }
            }
            return;
        }

        // As this crawl(HtmlPage page) method gets called dynamically, it is possible that
        // the website's dynamic code has redirected us out to a different website, so we 
        // need to check and if so backout
        Matcher m = websiteP.matcher(pageUrl); 
        if (m.matches() == false) { 
            printlnV(MARGIN + "Dynamic link/route sent us outside this website. Backing out and back buttoning!");
            try { webClient.getWebWindows().get(0).getHistory().back(); }
            catch (Exception e) {
                UrlTracker.addErrored(pageUrl);
                System.err.println("Back button after dynamic link invocation failed.");
                if (trace) { e.printStackTrace(); }
            }
            return;
        }

       // Now do the actual crawling by traversing from the HtmlPage page loaded
        traverse (page, pageUrl);

    } // End crawl

    /**
    * <p>traverse checks if the current page has been visited before (by calling the 
    * helper class  <code>UrlTracker.hasBeenVisited()</code> method). If the page has 
    * not been visited, traverse looks for text and comment nodes that might
    * contain email strings by calling <code>searchNodes<code> on all HTML nodes containing 
    * text, comments included.</p>
    * 
    * <p>In addition, static links are collected, and dynamic routes visited via simulating 
    * HtmlUnit's click action. <code>traverse</code> then calls <code>visitDynamicLinks</code> 
    * and <code>visitStaticLinks</code>on these reachable links, which results in recursion.</p>
    */
    private static void traverse (HtmlPage page, String pageUrl) {

        // Mark we have been here!
        UrlTracker.addVisited(pageUrl);

        // The page is open and loaded, now we can search...
        searchNodes(page, pageUrl, "//text()[contains(.,\"@\")]"); // all DOM text nodes &
        searchNodes(page, pageUrl, "//comment()[contains(.,\"@\")]"); // all html comments
        searchNodes(page, pageUrl, "a[starts-with(@href, 'mailto')]/text()"); // all mailtos


        try {

            // Here we click and crawl recursively on all dynamic links
            visitDynamicLinks (page, pageUrl);
    
            // Here we crawl recursively on all static links
            visitStaticLinks (page, pageUrl);

            // HtmlUnit needs this call to release resources...
            webClient.close();

        } catch (StackOverflowError e) {
            // Need to explore the root cause of the overflow a bit better... 
            UrlTracker.addErrored(pageUrl);
            printlnV("StackOverflow to get to this link depth! Increase, if possible -Xss param at the command line!");
            if (trace) { e.printStackTrace(); }
            return;
        } catch (Exception e) {
            UrlTracker.addErrored(pageUrl);
            printlnV("Unexpected error while visiting and traversing links!");
            if (trace) { e.printStackTrace(); }
            return;

        }

    }

    /**
    * searchNodes visits the current page for DOM nodes, and then looks 
    * looking for strings that might be emails in each of these nodes.mic routes
    */
    private static void searchNodes (HtmlPage page, String pageUrl, String xPath) {


        // Ask from xPath text that has one (or more) embedded '@', store them in 'possibles'
        List<?> possibles;
        try { possibles = page.getByXPath(xPath); }
        catch (Exception e) {
            printlnV("HtmlUnit's searchNode XPATH=" + xPath + " died for this particular page! ");
            if (trace) { e.printStackTrace(); }
            return;
        }
        printlnT("---- Possibilities found: " + possibles.size());

        // Now need to search these text fragments for actual emails.
        Set<String> emails = new HashSet<String>();
        possibles.forEach((i) -> {
            String[] fragments = i.toString().split(DELIMS);
            for ( String s : fragments ) {
                // We take as possibles anything of the form a@b.c where a, b, c have 1 or more chars in lenght
                // We reject a@b., a@local, or @life, some emails with embedded quotes might be lost :(
                if (s.indexOf('@') > 0 && s.indexOf('.') > 2 && (s.length() - s.indexOf('.')) >= 1) {
                    System.out.println(MARGIN + s + ANSI_RED + "\n" +  MARGIN + "^^^ Likely an Email!"
                        + ANSI_RESET + " [at " + pageUrl +"]");
                } 
            }
        });

    } // End searchNodes

    // An enum for visitStaticLinks() and getStaticLinks(), actions for ANCHOR and HREF 
    // are nearly identical
    private enum LinkType { ANCHOR, HREF }

    /**
    * visitStaticLinks visits all static links included in the 
    * set <code> links </code>
    */
    private static void visitStaticLinks (HtmlPage page, String pageUrl) {

        // Get the anchors, then the HREFS, store them in links
        Set<String> links = getStaticLinks (page, pageUrl, LinkType.ANCHOR);
        links.addAll (getStaticLinks (page, pageUrl, LinkType.HREF));

        // Now we recurse on the static content that we accumulated
        links.forEach((i) -> {
            printlnT("---- traversing static link: " + i);
            if (UrlTracker.hasBeenVisited(i) == false) {
                // We visit a URL we have not visited
                try { crawl(i); }
                catch (Exception e) {
                    // If we error out, skip that link but try the others!
                    UrlTracker.addErrored(i);
                    printlnV("visitStaticLinks, exception caught when crawling " + i);
                    if (trace) { e.printStackTrace(); }
                }
            }
        });
    } // end Visit Static Links


    /**
    * getStaticLinks visits the anchors and hyperlink expressions of the current page
    * let it be ANCHORs or HREFs based on the input of the parameter <code>type</type> 
    * and returns them as a set.
    */
    private static Set<String> getStaticLinks (HtmlPage page, String pageUrl, LinkType type) {


        // What we find as a list, might have many duplicates
        List<String> linksAsList = null;

        if (type == LinkType.ANCHOR) {
            // Get all the links that might be stored in elements understood as anchors by the browser
            linksAsList = page.getAnchors().stream()
                .map(Object::toString)
                .collect(Collectors.toList());
            printlnT("At " + pageUrl + "\nTotal anchors found " + linksAsList.size());
        }
        else if (type == LinkType.HREF) {
            // Get all links that are understood as HREFs by the browser
            linksAsList = page.getByXPath("//link").stream()
                .map(Object::toString)
                .collect(Collectors.toList());
            printlnT("At " + pageUrl + "\nLinks found " + linksAsList.size());
        } else {
            System.err.println ("Error! Unsuported type " + type);
            return null;
        }

        // We will eliminate duplicates by returning a set, case insensitive
        Set<String> linksAsSet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER); 
        linksAsList.forEach((i) -> {
            String iStr = i.toString(); 
            printlnT(MARGIN + iStr);
            Matcher m1 = absHrefP.matcher(iStr);
            Matcher m2 = relHrefP.matcher(iStr);
            if (m1.matches()) { linksAsSet.add(m1.group(3)); }
            if (m2.matches()) { linksAsSet.add(uri + m2.group(3)); }
            m1.reset(); m2.reset();
        });
        printlnT("------ Possible Links (Anchors & HREFs) to Drill Into: -----");
        linksAsList.forEach((i) -> { printlnT(MARGIN + i); });
        printlnT("------ Count: " + linksAsList.size());


        // Now filter and remove static links to (presumed) binaries we do not search,
        // return the output
        
        return filterByType(linksAsSet);
    } // getStaticLinks

    /**
    * visitDynamicLinks finds clickable items in the current page 
    * and executes a simulated HtmlUnit click action on them. It then
    * passes the new current page to  <code> crawl(HtmlPage page)</code>
    */
    private static void visitDynamicLinks (HtmlPage page, String pageUrl) {

        // >>> Future upgrades! Here more logic could be added for buttons, RoR dynamic content, ASP, etc... *****/

        // Now get list of all elements that Angular uses for clickable dynamic links
        final List<?> clickables = page.getByXPath("//*[contains(@ng-click,'changeRoute')]");
        printlnT("At: " + pageUrl);
        printlnT("elements w/ dynamic click hooks: " + clickables.size());
        clickables.forEach((i) -> { printlnT(i.toString());});
        printlnT("-------------------------");

        // Now here we click on the dynamic content, return via "Back" and keep collecting static content
        clickables.forEach((i) -> {
            printlnT("HtmlUnit browser getting page via simulated click of " + i.toString());
            HtmlPage newPage;
            try { newPage = ((DomElement) i).click(); }
            catch (Exception e) {
                System.err.println("Click action on " + i.toString() + " failed. Skipping it");
                if (verbose) { e.printStackTrace(); }
                return;
            }
            // Crawl the new page we have arrived at!
            crawl (newPage);

        });
    } // End visitDynamicLinks


    /**
    * filterByType removes links to static links such as pngs, jpgs, icos, and
    * under types that we cannot currently handle, which are stored in the regex
    * skipP 
    */
    private static Set<String> filterByType(Set<String> urls) {

        Set<String> urlsOut = new HashSet<String>();
        urls.forEach((i) -> {
            Matcher m = skipP.matcher(i);
            if (m.matches()) {
                printlnT(MARGIN + "Skipping due to filetype: " + i);
            } else { 
                // It did not match the filter, we keep it!
                urlsOut.add(i);
            } 
            m.reset();
        });
        return urlsOut;
    }


    /**
    * setWebClient sets up the HtmlUnit web client (i.e. the simulated browser) with
    * many settings that have proven correct in the field. 
    */
    private static void setWebClient() {
        // Turn the logger for htmlUnit off, otherwise we will be swamped indeed
        java.util.logging.Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);

        // Get the headless browser
        try { webClient = new WebClient(BrowserVersion.FIREFOX_38); }
        catch (Exception e) {
            printlnV("Could not open browser window! uri=" + uri);
            e.printStackTrace();
            printlnV("Exiting...");
            System.exit(1);
        }

        // Suppress myriad of javascript errors, like real browsers do
        webClient.getOptions().setThrowExceptionOnScriptError(false);

        // Set up the web client with many values that have proven to work in the field
        // Take some off, everything goes haywire. Know it by experience!
        webClient.getOptions().setCssEnabled(true); 
        webClient.setCssErrorHandler(new SilentCssErrorHandler());
        webClient.setAjaxController(new NicelyResynchronizingAjaxController()); 
        webClient.waitForBackgroundJavaScript(10 * 1000);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false); 
        webClient.getOptions().setThrowExceptionOnScriptError(false); 
        webClient.getOptions().setRedirectEnabled(false); 
        webClient.getOptions().setAppletEnabled(false); 
        webClient.getOptions().setJavaScriptEnabled(true); 
        webClient.getOptions().setPopupBlockerEnabled(true); 
        webClient.getOptions().setTimeout(5000); 
        webClient.getOptions().setPrintContentOnFailingStatusCode(false); 

    } // end setWebClient

   /**
    * processArgs processes the command line.
    * The command line can have the following form: </p>
    *
    * <p> ...Mailx <an uri to crawl from> [-v|-t], i.e. ...Mailx http://mysite.com -v </p>
    * <p> The Optional argument -v can be provided for verbose output, 
    *  Use -t for trace/debug output</p>
    */
    private static void processArgs(String[] arguments) {

        if (arguments.length == 0) {
            System.err.println("Usage: ...Mailx <uri> [-v|-t], i.e. ...Mailx 'http://mysite.com' -v");
            System.err.println("Optional argument -v can be provided for verbose output, -t for trace/debug output");
            System.exit(1);
        }
        if (containsPattern(arguments[0], "^http://.+") == false) {
            System.err.println("Input: " + arguments[0] + " invalid! Please include http:// as prefix!");
            System.exit(1);
        }
        if (arguments.length >= 2 && containsPattern(arguments[1], "^-t.*") == true) {
            System.out.println("Trace mode is on!");
            trace = true;
        }
        if (arguments.length >= 2 && containsPattern(arguments[1], "^-v.*") == true) {
            System.out.println("Verbose mode is on!");
            verbose = true;
        }

        // An URI argument with some possibility of success, make sure it has an ending '/', needed for our regExes
        startPage = arguments[0];
        if(startPage.charAt(startPage.length() -1) != '/') { startPage = new String(arguments[0] + '/'); }

        // Now extract the URI
        Pattern uriOnly = Pattern.compile("(^http://[^/]+/)(.*$)");
        Matcher m = uriOnly.matcher(startPage);
        if (m.matches() == false) {
            System.err.println("Please check your entry! Cannot extract the URI from " + startPage);
            System.exit(1);
        } else {
            // We have a good URI, we can now build regex to match absolute URIs
            uri = m.group(1); m.reset();
            System.out.println("Starting Page: " + startPage);
            System.out.println("URI: " + uri);
            absHrefP = Pattern.compile("(^.*)(href=\")(" + uri +"[^\"]+)(.*$)");
            websiteP = Pattern.compile("(^" + uri +")(.*$)");
        }
    } // end processArgs


    /**
    * Check if a given pattern exists in a string.
    */
    private static boolean containsPattern(String string, String regex) {
        Pattern pattern = Pattern.compile(regex);

        // Check for the existence of the pattern
        Matcher matcher = pattern.matcher(string);
        return matcher.find();
    } // end containsPattern

   /**
    * Print to the console if verbose mode is on (-v at the command line).
    * Also prints when trace mode is on.
    */
    private static void printlnV(String str) {
        // Print out if verbose or trace mode are on
        if (verbose == true || trace == true) { System.out.println(str); }
    } // end printlnT

   /**
    * Print to the console if trace mode is on (-t at the command line)
    */
    private static void printlnT(String str) {
        // Print out only if trace mode is on
        if (trace == true) { System.out.println(str); }
    } // end printlnT


    // Helper static nested classes //

   /**
    * The UrlTracker class encapsulates the set of urls that that have already been visited, 
    * and the set of urls where we have errored out,
    */
    private static class UrlTracker {

        // Tracks pages where we have crawled
        static final Set<String> urlsVisited = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER); 

        // Tracks pages where we errored out, we do not try to revisit them
        static final Set<String> urlsErrored = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);


       /**
        * Call addVisited() when needing to indicate that String url has been visited. 
        */
        private static boolean addVisited (String url) {
            // Add to the set of urls we have visited
            return urlsVisited.add (url);
        }

       /**
        * Call addErrored() when needing to indicate that visiting String url resulted in
        * an error. This url should not be visited nor marked as reachable in the future
        */
        private static boolean addErrored (String url) {
            printlnV("Adding to Error List. Could not successfully visit: " + url);
            return urlsErrored.add(url);
        }

       /**
        * Call hasBeenVisited() if wanting to figure out if this URL has been visited
        * either successfully, or even if we got an error
        */
        private static boolean hasBeenVisited(String url) {
            return (urlsVisited.contains(url) || urlsErrored.contains(url));
        } 

       /**
        * Print the class contents in a nice fashion
        */
        private static String summary() {
            return ("distinct urls visited: " + urlsVisited.size() + ", errorer out: " 
                + urlsErrored.size());
        } 


    } //end class UrlTracker

} // end class MailX