package zedbit.mailx;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import java.util.logging.Level;

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
    static Set<String> urlsVisited = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER); // Tracks pages where we have crawled
    static Set<String> urlsReachable = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER); // Tracks pages where crawling is OK
    static boolean verbose; // Are we running in verbose mode?
    static boolean trace; // Are we running in trace mode? Useful for debugging

    //Prepare to match on regexes for likely emails, very loose, to not miss domains such as .museum :)
    static final Pattern emailP = Pattern.compile("[\\S^@]+@[\\S^@]+");

    //We will follow two kinds of links, relative which we can build a regex for now, and absolute...
    static final Pattern relHrefP = Pattern.compile("(^.*)(href=\")(/[^\"]+)(.*$)");
    static Pattern absHrefP; // compiled at method processArgs once we have extracted our uri
    static Pattern websiteP; // as above, used to check dynamic links/routes to be within this website

    // Some constants for some pretty-printing 
    static final String ANSI_RED = "\u001B[31m";
    static final String ANSI_RESET = "\u001B[0m";
    static final String MARGIN = "        ";

    /**
    * Entry point for MailX, a simple scrapping sample program for email extraction.
    *
    * <p> @param arguments contains the commands passed as part of the invocation of the
    * class <code> Mailx </code> and can have the following form: </p>
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

        System.out.println ("Crawling completed!");

    } // end main


   
    /**
    * Crawl from url or uri @param pageUrl, limiting to only links sharing the same uri
    * (that is, do not crawl on subdomains). <code>Crawl(String pageUrl)</code> is called 
    * with @param pageUrl of class String for pages that are expressed via a static URL. 
    * When a page is visited as a result of a simulated dynamic action, such as a click, 
    * the <code>Crawl(HtmlPage)</code> method is invoked.
    */
    private static void crawl(String pageUrl) {

        // First, check if we have been at this URL before, we back out
        if (urlsVisited.contains(pageUrl) == true) {
            printlnT("We have already visited " + pageUrl + ". Skipping it");
            return;
        }

        // Connect and get the page, backout if we can't.
        printlnV("Crawling Static Link w/URL=" + pageUrl);
        HtmlPage page;  // This will be the page we will be working on, as represented by htmlUnit
        try { page = webClient.getPage(pageUrl); }
        catch (Exception e) {
            // Mark we have been here before even if we got an error!
            urlsVisited.add(pageUrl);
            if (verbose || trace) {
                System.err.println("Could not get page " + pageUrl);
                if (trace) { e.printStackTrace(); }
            }
            return;
        }

        // Now do the actual crawling by traversing from the HtmlPage page loaded
        traverse (page, pageUrl);

    } // End crawl

    /**
    * <p> Crawl from the current page dynamically reached by simulating a click. The current
    * page is passed in @param page. Notice that <code>Crawl(String pageUrl)</code> is called 
    * a String parameter for pages that are expressed via a static URL.<p/>
    *
    * Because the URL of a page resulting from a script/route cannot be anticipated until
    * the browser is called, this page might not be inside this website. If this is the case
    * the method backs out of this page. To back out the code simulates pressing the "back"
    * button of the simulated browser.
    * 
    */
    private static void crawl(HtmlPage page) {

        final String pageUrl = page.getUrl().toString();
        printlnV("Crawling Dynamic Link w/URL=" + pageUrl);

        // Now check if we have been at this URL before, OR if this dynamic
        // link is actually not in this website, if so we back out
        if (urlsVisited.contains(pageUrl) == true) {
            printlnV(MARGIN + pageUrl + " already crawled. Back buttoning it");
            try { webClient.getWebWindows().get(0).getHistory().back(); }
            catch (Exception e) {
                System.err.println("Back button after dynamic link invocation failed.");
                if (verbose) { e.printStackTrace(); }
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
                System.err.println("Back button after dynamic link invocation failed.");
                if (verbose) { e.printStackTrace(); }
            }
            return;
        }

       // Now do the actual crawling by traversing from the HtmlPage page loaded
        traverse (page, pageUrl);

    } // End crawl

    /**
    * <p>traverse checks if the current page, represented by @param page with @param pageUrl URL,
    * has been visited before (by checking against the contents of the <code>urlsVisited</code> 
    * set). If the page has not been visited, traverse looks for text and comment nodes that might
    * contain email strings by calling <code>searchNodes<code> on all HTML nodes containing 
    * text, comments included.</p>
    * 
    * <p>In addition, static links are collected, and dynamic routes visited via simulating 
    * HtmlUnit's click action. <code>traverse</code> then calls <code>visitDynamicLinks</code> 
    * and <code>visitStaticLinks</code>on these reachable links, which results in recursion.</p>
    */
    private static void traverse (HtmlPage page, String pageUrl) {

        // Mark we have been here!
        urlsVisited.add(pageUrl);

        // The page is open and loaded, now we can search...
        searchNodes(page, pageUrl, "//text()[contains(.,\"@\")]"); // all DOM text nodes &
        searchNodes(page, pageUrl, "//comment()[contains(.,\"@\")]"); // all html comments


        // Now get all static links to crawl along these, accumulate then in urlsReachable
        getStaticLinks (page, pageUrl);

        // Here we click and crawl recursively on all dynamic links
        visitDynamicLinks (page, pageUrl);
    
        // Now crawl recursively on all static links
        visitStaticLinks (page, pageUrl);

    }

    /**
    * searchNodes visits the current @param page with @param pageUrl URL, for 
    * DOM nodes specified by the XPATH @param xPath expression, and then looks 
    * looking for strings that might be emails in each of these nodes.mic routes
    */
    private static void searchNodes (HtmlPage page, String pageUrl, String xPath) {


        Set<String> eMails = new HashSet<String>();
        List<?> possibles;
        try { possibles = page.getByXPath(xPath); }
        catch (Exception e) {
            printlnV("HtmlUnit's searchNode XPATH=" + xPath + " died for this particular page! ");
            if (trace) { e.printStackTrace(); }
            return;
        }

        printlnT("---- Possibilities found: " + possibles.size());
        possibles.forEach((i) -> {
            String iStr = i.toString();
            printlnT("---- possible: " + iStr);
            Matcher m = emailP.matcher(iStr);
            while (m.find()) {
                eMails.add(iStr);
                System.out.println(MARGIN + iStr + ANSI_RED + "\n" +  MARGIN + "^^^ Likely an Email!"
                    + ANSI_RESET + " [at " + pageUrl +"]");
            }
            m.reset();
        });
        printlnT("total unique local or relative emails found " + eMails.size());

    } // End searchNodes

    /**
    * getStaticLinks visits the anchors and hyperlink expressions of the current @param page 
    * with @param pageUrl URL, and addes them to the class global set <code> urlsReachable </code>
    */
    private static void getStaticLinks (HtmlPage page, String pageUrl) {

        // First get all the links that might be stored in elements understood as anchors by the browser
        Set<String> localAnchors = new HashSet<String>();
        final List<HtmlAnchor> allAnchors = page.getAnchors();
        printlnT("At " + pageUrl + "\nTotal anchors found " + allAnchors.size());
        allAnchors.forEach((i) -> {
            String iStr = i.toString();
            printlnT(MARGIN + iStr);
            Matcher m1 = absHrefP.matcher(iStr); // Match absolute links sharing local Uri
            Matcher m2 = relHrefP.matcher(iStr); // Match any relative links
            if (m1.matches()) { localAnchors.add(m1.group(3)); m1.reset(); }
            if (m2.matches()) { localAnchors.add(m2.group(3)); m2.reset(); }
        });
        printlnT("---- Anchors to Drill Into: -----");
        localAnchors.forEach((i) -> { printlnT(MARGIN + i); });
        printlnT("---- Total unique local or relative anchors found: " + localAnchors.size() + "\n");

        //get list of all hrefs that are understood as a link by the browser
        Set<String> localLinks = new HashSet<String>();
        final List<?> allLinks = page.getByXPath("//link");
        printlnT("At " + pageUrl + "\nLinks found " + allLinks.size());
        allLinks.forEach((i) -> {
            String iStr = i.toString();
            printlnT(MARGIN + iStr);
            Matcher m1 = absHrefP.matcher(iStr);
            Matcher m2 = relHrefP.matcher(iStr);
            if (m1.matches()) { localLinks.add(m1.group(3)); m1.reset(); }
            if (m2.matches()) { localLinks.add(m2.group(3)); m2.reset(); }
        });
        printlnT("------ Links to Drill Into: -----");
        localLinks.forEach((i) -> { printlnT(MARGIN + i); });
        printlnT("------ Total unique local or relative links found: " + localLinks.size());

        // Now lets add the links we extracted into those we need to visit. Reachable is set to
        // compare string values and ignore case, so duplicate urls are eliminated
        // Case sensitiveness better be off in all Apaches, as DNS names are case insensitive :)
        printlnT("------ Reachable URL count before adding links and anchors: " + urlsReachable.size());
        urlsReachable.addAll(localAnchors);
        urlsReachable.addAll(localLinks);
        printlnT("------ Reachable URL count after adding these links: " + urlsReachable.size());

    } // getStaticLinks

    /**
    * visitDynamicLinks finds clickable items in the current specified by @param page 
    * with @param pageUrl URL, and executes a simulated HtmlUnit click action. It then
    * passes this new current page to  <code> crawl(HtmlPage page)</code>
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
            // Wait a bit for the page's JavaScript and HtmlUnit to catch up.
            webClient.waitForBackgroundJavaScript(100);
            crawl (newPage);
    
        });
    } // End visitDynamicLinks

    /**
    * visitStaticLinks visits static links we have accumulated after examining
    * the current page (matching @param page, with URL @paramUrl). The parameters
    * are used only for printing to the console if executing under the verbose
    * or trace modes. The URLs listed in the set <code>urlsReachable</code> that
    * are not present in the <code>urlsVisited</code> are visited methodically instead.
    */
    private static void visitStaticLinks (HtmlPage page, String pageUrl) {
        // Now we recurse on the static content that we accumulated
        try {
            urlsReachable.forEach((i) -> {
                printlnT("---- traversing static link: " + i);
                if (urlsVisited.contains(i) == false) {
                    // We visit a URL we have not visited
                    printlnT("---- will crawl " + i);
                    webClient.waitForBackgroundJavaScript(100);
                    crawl(i);
                }
            });
            // Now we can empty the set, as we have visited them all, or tried to
            urlsReachable.clear();
        } catch (Exception e) {
            printlnV("Cannot iterate further! Async JavaScript HtmlUnit problems?!");
            urlsReachable.clear();
            if (trace) { e.printStackTrace(); }
            return;
        }
    } // end Visit Static Links

    /**
    * setWebClient sets up the HtmlUnit web client (i.e. the simulated browser) with
    * many settings that have proven correct in the field. Obtained via searches on
    * StackOverflow.
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
    * processArgs processes the command line contained in @param arguments
    * Arguments can have the following form: </p>
    *
    * <p> ...Mailx <an uri to crawl from> [-v|-t], i.e. ...Mailx http://mysite.com -v </p>
    * <p> The Optional argument -v can be provided for verbose output, 
    * -t for trace/debug output where all of the URLs of all pages visited are written to the screen,
    * with the exception of HtmlUnit logging, which is extremely verbose</p>
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


    // Some minor utility functions

    private static boolean containsPattern(String string, String regex) {
        Pattern pattern = Pattern.compile(regex);

        // Check for the existence of the pattern
        Matcher matcher = pattern.matcher(string);
        return matcher.find();
    } // end containsPattern

    private static void printlnV(String str) {
        // Print out if verbose or trace mode are on
        if (verbose == true || trace == true) { System.out.println(str); }
    } // end printlnT

    private static void printlnT(String str) {
        // Print out only if trace mode is on
        if (trace == true) { System.out.println(str); }
    } // end printlnT

} // end class MailX