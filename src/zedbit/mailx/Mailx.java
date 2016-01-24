package zedbit.mailx;

import java.util.logging.Logger;
import java.util.logging.Level;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.BrowserVersion;

import com.gargoylesoftware.htmlunit.html.DomText;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.DomElement;


/**
 * A simple scrapping for emails
 *
 * @author Manuel Mendez
 * @since January 22, 2016
 */
public class Mailx {

	// Member variables
    static WebClient webClient; // The headless browser
    static String startPage; // Where the crawl begins, may be a URI or a page
    static String uri; // The URI 
    static Set<String> urlsVisited = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER); // Tracks pages where we have crawled
    static Set<String> urlsReachable = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER); // Tracks pages where crawling is OK
    static boolean verbose; // Are we running in verbose mode?

	//Prepare to match on regexes for likely emails, very loose, to not miss domains such as .museum :)
    static final Pattern emailP = Pattern.compile("[\\S^@]+@[\\S^@]+");

    //We will follow two kinds of links, relative which we can build a regex for now, and absolute...
    static final Pattern relHrefP = Pattern.compile("(^.*)(href=\")(/[^\"]+)(.*$)");
    static Pattern absHrefP; // compiled at method processArgs once we have extracted our uri

    // Some constants for some pretty-printing 
    static final String ANSI_RED = "\u001B[31m";
    static final String ANSI_RESET = "\u001B[0m";
    static final String MARGIN = "        ";

	/**
 	* Entry point, a simple scrapping sample program for email extraction.
 	*
 	* @author Manuel Mendez
 	* @since January 22, 2016
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
 	* Crawl from url or uri startPage, limiting to only links sharing the same uri
 	* (that is, do not crawl on subdomains)
 	*
 	* @author Manuel Mendez
 	* @since January 22, 2016
 	*/
	public static void crawl(String pageUrl) {

  		// First, check if we have been at this URL before, we back out
  		if (urlsVisited.contains(pageUrl) == true) {
	        printlnV("We have already visited " + pageUrl + ". Skipping it");
	        return;
  		}

    	// Connect and get the page, backout if we can't.
        System.out.println("HtmlUnit browser getting page " + pageUrl);
		HtmlPage page;	// This will be the page we will be working on, as represented by htmlUnit
		try { page = webClient.getPage(pageUrl); }
		catch (Exception e) {
			// Mark we have been here before even if we got an error!
			urlsVisited.add(pageUrl); 
        	if (verbose) { 
				System.err.println("Could not get page " + pageUrl + " " + e.toString());
        		e.printStackTrace(); }
			return;
		} 

		// Mark we have been here before
   		urlsVisited.add(pageUrl); // Use htmlUnit's name of the page, or our name? A question that remains...

  		// The page is open and loaded, now we can search...
   		searchNodes(page, pageUrl, "//text()[contains(.,\"@\")]"); // all DOM text nodes &
		searchNodes(page, pageUrl, "//comment()[contains(.,\"@\")]"); // all html comments

		// Now get all static links to crawl along these, accumulate then in urlsReachable
		getStaticLinks (page, pageUrl);

		// Here we click and crawl recursively on all dynamic links
		visitDynamicLinks (page, pageUrl); 

		// Now crawl recursively on all static links
		visitStaticLinks (page, pageUrl);

    } // End crawl


	public static void crawl(HtmlPage page) {

		final String pageUrl = page.getUrl().toString();
		printlnV("Crawling HtmlPage instace with URL=" + pageUrl); 

		// Now check if we have been at this URL before, we back out
		if (urlsVisited.contains(pageUrl) == true) {
		    printlnV("We have already visited " + pageUrl + ". Skipping it");
	    	try { webClient.getWebWindows().get(0).getHistory().back(); }
	    	catch (Exception e) {
       			System.err.println("Back button after dynamic link invocation failed.");
 	            if (verbose) { e.printStackTrace(); }		
	    	}
		    return;
		}

		// Mark we have been here before!
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

	} // End crawl


	private static void searchNodes (HtmlPage page, String pageUrl, String xPath) {

       	Set<String> eMails = new HashSet<String>();
        final List<?> possibles = page.getByXPath(xPath);
        printlnV("---- Possibilities found: " + possibles.size());
        possibles.forEach((i) -> {
        	String iStr = i.toString();
        	printlnV("---- possible: " + iStr);
        	Matcher m = emailP.matcher(iStr);
        	while (m.find()) { 
        		eMails.add(iStr);
        		System.out.println(MARGIN + iStr + ANSI_RED + "\n" +  MARGIN + "^^^ Likely an Email!" 
        			+ ANSI_RESET + " [at " + pageUrl +"]");
        	}
        	m.reset();
        });
        printlnV("total unique local or relative emails found " + eMails.size());

    } // End searchNodes


	private static void getStaticLinks (HtmlPage page, String pageUrl) { 

		// First get all the links that might be stored in elements understood as anchors by the browser
	    Set<String> localAnchors = new HashSet<String>();
        final List<HtmlAnchor> allAnchors = page.getAnchors();
        printlnV("At " + pageUrl + "\nTotal anchors found " + allAnchors.size());
        allAnchors.forEach((i) -> {
        	String iStr = i.toString();
        	printlnV(MARGIN + iStr);
        	Matcher m1 = absHrefP.matcher(iStr); // Match absolute links sharing local Uri
        	Matcher m2 = relHrefP.matcher(iStr); // Match any relative links
        	if (m1.matches()) { localAnchors.add(m1.group(3)); m1.reset(); }
        	if (m2.matches()) { localAnchors.add(m2.group(3)); m2.reset(); }
        });
       	printlnV("---- Anchors to Drill Into: -----");
        localAnchors.forEach((i) -> { printlnV(MARGIN + i); });
        printlnV("---- Total unique local or relative anchors found: " + localAnchors.size() + "\n");

	    //get list of all hrefs that are understood as a link by the browser
	    Set<String> localLinks = new HashSet<String>();
        final List<?> allLinks = page.getByXPath("//link");
        printlnV("At " + pageUrl + "\nLinks found " + allLinks.size());
        allLinks.forEach((i) -> {
        	String iStr = i.toString();
        	printlnV(MARGIN + iStr);
        	Matcher m1 = absHrefP.matcher(iStr);
        	Matcher m2 = relHrefP.matcher(iStr);
        	if (m1.matches()) { localLinks.add(m1.group(3)); m1.reset(); }
        	if (m2.matches()) { localLinks.add(m2.group(3)); m2.reset(); }
        });
        printlnV("------ Links to Drill Into: -----");
        localLinks.forEach((i) -> { printlnV(MARGIN + i); });
        printlnV("------ Total unique local or relative links found: " + localLinks.size());

       	// Now lets add the links we extracted into those we need to visit. Reachable is set to 
       	// compare string values and ignore case, so duplicate urls are eliminated
       	// Case sensitiveness better be off in all Apaches, as DNS names are case insensitive :)
       	printlnV("------ Reachable URL count before adding links and anchors: " + urlsReachable.size());
       	urlsReachable.addAll(localAnchors);
       	urlsReachable.addAll(localLinks);
       	printlnV("------ Reachable URL count after adding these links: " + urlsReachable.size());

    } // getStaticLinks


	static void visitDynamicLinks (HtmlPage page, String pageUrl) {

       	// >>> Future upgrades! Here more logic could be added for buttons, RoR dynamic content, ASP, etc... *****/

        // Now get list of all elements that Angular uses for clickable dynamic links
        final List<?> clickables = page.getByXPath("//*[contains(@ng-click,'changeRoute')]");
        printlnV("At: " + pageUrl);
        printlnV("elements w/ dynamic click hooks: " + clickables.size());
        clickables.forEach((i) -> { printlnV(i.toString());});
       	printlnV("-------------------------");

       	// Now here we click on the dynamic content, return via "Back" and keep collecting static content
        clickables.forEach((i) -> {
       		System.out.println("HtmlUnit browser getting page via simulated click of " + i.toString());
       		HtmlPage newPage;
       		try { newPage = ((DomElement) i).click(); }
       		catch (Exception e) {
       			System.err.println("Click action on " + i.toString() + " failed. Skipping it");
 	            if (verbose) { e.printStackTrace(); }
				return;
       		}
       		crawl (newPage);
    
       	});

    }


    private static void visitStaticLinks (HtmlPage page, String pageUrl) {
       	// Now we recurse on the static content that we accumulated
        try {
        	urlsReachable.forEach((i) -> {
        		printlnV("---- traversing static link: " + i);
 				if (urlsVisited.contains(i) == false) {
 					// We visit a URL we have not visited
 	 	      		printlnV("---- will crawl " + i);
 					crawl(i);
 				}
        	});
        } catch (Exception e) {
      		System.err.println("Cannot iterate further! HtmlUnit problems!");
 	        if (verbose) { e.printStackTrace(); }
 	        return;		
        }
    } // End Visit Static Links

    private static void setWebClient() {
    	// Turn the logger for htmlUnit off, otherwise we will be swamped indeed
		java.util.logging.Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);

		// Get the headless browser 
		try { webClient = new WebClient(BrowserVersion.FIREFOX_38); }
		catch (Exception e) {
			System.err.println("Could not open browser window! uri=" + uri);
			e.printStackTrace();
        	System.err.println("Exiting...");
			System.exit(1);
        }

   	    // Suppress myriad of javascript errors, like real browsers do
    	webClient.getOptions().setThrowExceptionOnScriptError(false);

    	// Set up the web client with many values that have proven to work in the field
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


    private static void processArgs(String[] arguments) {

		if (arguments.length == 0) {
	    	System.err.println("Usage: ...Mailx <uri> [-v], i.e. ...Mailx 'http://mysite.com' -v");
		      	System.err.println("Optional argument -v can be provided for verbose output");
			System.exit(1);
		}
		if (containsPattern(arguments[0], "^http://.+") == false) {
	    	System.err.println("Input: " + arguments[0] + " invalid! Please include http:// as prefix!");
			System.exit(1);
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
		}
	} // end processArgs


    private static boolean containsPattern(String string, String regex) {
        Pattern pattern = Pattern.compile(regex);

        // Check for the existence of the pattern
        Matcher matcher = pattern.matcher(string);
        return matcher.find();
    } // end containsPattern


 	private static void printlnV(String str) {
    	// Print out only if verbose!
    	if (verbose == true) { System.out.println(str); }
    } // end printlnV


} // end class MailX