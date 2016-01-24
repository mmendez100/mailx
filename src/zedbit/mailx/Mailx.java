package zedbit.mailx;

import java.util.logging.Logger;
import java.util.logging.Level;


import java.util.List;
import java.util.Set;
import java.util.HashSet;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.DomText;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.DomElement;


import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.BrowserVersion;



import java.util.regex.Matcher;
import java.util.regex.Pattern;



/**
 * A simple scrapping for emails
 *
 * @author Manuel Mendez
 * @since January 22, 2016
 */
public class Mailx {

    static WebClient webClient; // The headless browser
    static String startPage; // Where the crawl begins, may be a URI or a page
    static String uri; // The URI 
    static Set<String> pagesVisited = new HashSet<String>(); // Tracks pages where we have crawled

	//Prepare to match on regexes for likely emails, very loose, to not miss domains such as .museum :)
    static final Pattern emailP = Pattern.compile("[\\S^@]+@[\\S^@]+");

    // We will follow only two kinds of links, absolute with our uri, or relative
    static final Pattern absHrefP = Pattern.compile("(^.*)(href=\")(" + uri +"[^\"]+)(.*$)");
    static final Pattern relHrefP = Pattern.compile("(^.*)(href=\")(/[^\"]+)(.*$)");

    // Some constants for some pretty-printing 
    static final String ANSI_RED = "\u001B[31m";
    static final String ANSI_RESET = "\u001B[0m";

	/**
 	* Entry point, a simple scrapping sample program for email extraction.
 	*
 	* @author Manuel Mendez
 	* @since January 22, 2016
 	*/
    public static void main(String[] arguments) {
    	if (arguments.length == 0) {
        	System.err.println("Usage: ...Mailx <uri>, i.e. ...Mailx 'http://mysite.com'");
			System.exit(1);
    	}
    	if (containsPattern(arguments[0], "^http://.+") == false) {
        	System.err.println("Input: " + arguments[0] + " invalid! Please include http:// as prefix!");
			System.exit(1);
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
			// We have a good URI
       	 	uri = m.group(1); m.reset();
	        System.out.println("Starting Page: " + startPage);
	        System.out.println("URI: " + uri);
       	 }

    	// Some setup, turn off superverbose htmlunit logger
       	java.util.logging.Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF); 

       	// Get a headless browser
        try { webClient = new WebClient(BrowserVersion.FIREFOX_38); }
        catch (Exception e) {
            System.err.println("Could not open browser window! uri=" + uri);
            e.printStackTrace();
        	System.err.println("Exiting...");
			System.exit(1);
        }

       	// Now begin the drill down!
		crawl(startPage);

    } // end main

	/**
 	* Entry point, a simple scrapping sample program for email extraction.
 	*
 	* @arg pageUrl is an htmlUnit page open (instance of HtmlPage) or a
 	*      string representing a page to open.
 	* @author Manuel Mendez
 	* @since January 22, 2016
 	*/
	public static void crawl(String pageUrl) {

  		// First, check if we have been at this URL before, we back out
  		if (pagesVisited.contains(pageUrl) == true) {
	        System.out.println("We have already visited " + pageUrl + ". Skipping it");
  		}

 	    // Suppress myriad of javascript errors, like real browsers do
    	webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setCssEnabled(true); 
 		webClient.setCssErrorHandler(new SilentCssErrorHandler()); 
        webClient.setAjaxController(new NicelyResynchronizingAjaxController());
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false); 
        webClient.getOptions().setThrowExceptionOnScriptError(false); 
        webClient.getOptions().setRedirectEnabled(false); 
        webClient.getOptions().setAppletEnabled(false); 
        webClient.getOptions().setJavaScriptEnabled(true); 
        webClient.getOptions().setPopupBlockerEnabled(true); 
        webClient.getOptions().setTimeout(5000); 
        webClient.getOptions().setPrintContentOnFailingStatusCode(false); 
    		
    	// Connect and get the page, backout if we can't.
        System.out.println("Simulated browser getting page " + pageUrl);
		HtmlPage page;	// This will be the page we will be working on, as represented by htmlUnit
		try { page = webClient.getPage(pageUrl); }
		catch (Exception e) {
        	System.err.println("Could not get page " + uri);
        	e.printStackTrace();
				return;
		} 

		// Some time for background javascript
	    System.out.println("Simulated browser waiting for background JavaScript completion ");
		webClient.waitForBackgroundJavaScript(1 * 1000);
   		System.out.println("-------------------------");

  		// FINALLY The page is open and loaded, now we can search!

        //First pass of what we want, any email possibilities that have '@'
       	Set<String> eMails = new HashSet<String>();
        final List<?> possibles = page.getByXPath("//text()[contains(.,\"@\")]");
        System.out.println("possibilities found " + possibles.size());
        possibles.forEach((i) -> {
        	String iStr = i.toString();
        	System.out.println(iStr);
        	Matcher m = emailP.matcher(iStr);
        	while (m.find()) { 
        		eMails.add(iStr);
        		System.out.println(iStr + ANSI_RED + " <<< Likely an Email!" + ANSI_RESET);
        	}
        	m.reset();
        });
        System.out.println("total unique local or relative emails found " + eMails.size());
       	System.out.println("-------------------------");

        //get list of all the comments
        final List<?> comments = page.getByXPath("//comment()[contains(.,\"@\")]");
        System.out.println("comments found " + comments.size());
        comments.forEach((i) -> {System.out.println(i.toString());});
       	System.out.println("-------------------------");


	    //get list of all anchors that contain either the uri or a local reference
	    Set<String> localAnchors = new HashSet<String>();
        final List<HtmlAnchor> allAnchors = page.getAnchors();
        System.out.println("total anchors found " + allAnchors.size());
        allAnchors.forEach((i) -> {
        	String iStr = i.toString();
        	System.out.println(iStr);
        	Matcher m1 = absHrefP.matcher(iStr);
        	Matcher m2 = relHrefP.matcher(iStr);
        	if (m1.matches()) { localAnchors.add(m1.group(3)); m1.reset(); }
        	if (m2.matches()) { localAnchors.add(m2.group(3)); m2.reset(); }
        });
       	System.out.println("---- Anchors to Drill Into: -----");
        localAnchors.forEach((i) -> { System.out.println(i); });
        System.out.println("---- Total unique local or relative anchors found: " + localAnchors.size());
       	System.out.println("-------------------------");

	    //get list of all links
	    Set<String> localLinks = new HashSet<String>();
        final List<?> allLinks = page.getByXPath("//link");
        System.out.println("links found " + allLinks.size());
        allLinks.forEach((i) -> {
        	String iStr = i.toString();
        	System.out.println(iStr);
        	Matcher m1 = absHrefP.matcher(iStr);
        	Matcher m2 = relHrefP.matcher(iStr);
        	if (m1.matches()) { localLinks.add(m1.group(3)); m1.reset(); }
        	if (m2.matches()) { localLinks.add(m2.group(3)); m2.reset(); }
        });
        System.out.println("------ Links to Drill Into: -----");
        localLinks.forEach((i) -> { System.out.println(i); });
        System.out.println("------ Total unique local or relative links found: " + localLinks.size());
       	System.out.println("-------------------------");


       //get list of all elements that Angular uses for clickable dynamic links
       final List<?> clickables = page.getByXPath("//*[contains(@ng-click,'changeRoute')]");

        System.out.println("elements w/ dynamic click hooks: " + clickables.size());
        clickables.forEach((i) -> {System.out.println(i.toString());});
       	System.out.println("-------------------------");

       	/***** Future upgrades! Here more logic could be added for buttons, RoR dynamic content, ASP, etc... *****/

       	// Now here we click on the dynamic content, return via "Back" and keep collecting static content
       clickables.forEach((i) -> {
       		System.out.println("Traversing the dynamic link " + i.toString());
       		HtmlPage newPage;
       		try { newPage = ((DomElement) i).click(); }
       		catch (Exception e) {
       			System.err.println("Click action on " + i.toString() + " failed. Skipping it");
 	            e.printStackTrace();
				return;
       		}
       		crawl (newPage);
       	});


       	// Now we recurse on the static content that we accumulated



	} // end crawl




	public static void crawl(HtmlPage page) {

		final String pageUrl = page.getBaseURL().toString(); 

		// Now check if we have been at this URL before, we back out
		if (pagesVisited.contains(pageUrl) == true) {
		    System.out.println("We have already visited " + pageUrl + ". Skipping it");
	    	try { webClient.getWebWindows().get(0).getHistory().back(); }
	    	catch (Exception e) {
       			System.err.println("Back button after dynamic link invocation failed.");
 	            e.printStackTrace(); 		
	    	}
		    return;
		}


		    System.out.println("Here we would explore " + pageUrl + ". NOOP now");
		    System.out.println(page.toString());

        //First pass of what we want, any email possibilities that have '@'
       	Set<String> eMails = new HashSet<String>();
        final List<?> possibles = page.getByXPath("//text()[contains(.,\"@\")]");
        System.out.println("possibilities found " + possibles.size());
        possibles.forEach((i) -> {
        	String iStr = i.toString();
        	System.out.println(iStr);
        	Matcher m = emailP.matcher(iStr);
        	while (m.find()) { 
        		eMails.add(iStr);
        		System.out.println(iStr + ANSI_RED + " <<< Likely an Email!" + ANSI_RESET);
        	}
        	m.reset();
        });
        System.out.println("total unique local or relative emails found " + eMails.size());
       	System.out.println("-------------------------");

        //get list of all the comments
        final List<?> comments = page.getByXPath("//comment()[contains(.,\"@\")]");
        System.out.println("comments found " + comments.size());
        comments.forEach((i) -> {System.out.println(i.toString());});
       	System.out.println("-------------------------");


	    //get list of all anchors that contain either the uri or a local reference
	    Set<String> localAnchors = new HashSet<String>();
        final List<HtmlAnchor> allAnchors = page.getAnchors();
        System.out.println("total anchors found " + allAnchors.size());
        allAnchors.forEach((i) -> {
        	String iStr = i.toString();
        	System.out.println(iStr);
        	Matcher m1 = absHrefP.matcher(iStr);
        	Matcher m2 = relHrefP.matcher(iStr);
        	if (m1.matches()) { localAnchors.add(m1.group(3)); m1.reset(); }
        	if (m2.matches()) { localAnchors.add(m2.group(3)); m2.reset(); }
        });
       	System.out.println("---- Anchors to Drill Into: -----");
        localAnchors.forEach((i) -> { System.out.println(i); });
        System.out.println("---- Total unique local or relative anchors found: " + localAnchors.size());
       	System.out.println("-------------------------");

	    //get list of all links
	    Set<String> localLinks = new HashSet<String>();
        final List<?> allLinks = page.getByXPath("//link");
        System.out.println("links found " + allLinks.size());
        allLinks.forEach((i) -> {
        	String iStr = i.toString();
        	System.out.println(iStr);
        	Matcher m1 = absHrefP.matcher(iStr);
        	Matcher m2 = relHrefP.matcher(iStr);
        	if (m1.matches()) { localLinks.add(m1.group(3)); m1.reset(); }
        	if (m2.matches()) { localLinks.add(m2.group(3)); m2.reset(); }
        });
        System.out.println("------ Links to Drill Into: -----");
        localLinks.forEach((i) -> { System.out.println(i); });
        System.out.println("------ Total unique local or relative links found: " + localLinks.size());
       	System.out.println("-------------------------");


       //get list of all elements that Angular uses for clickable dynamic links
       final List<?> clickables = page.getByXPath("//*[contains(@ng-click,'changeRoute')]");

        System.out.println("elements w/ dynamic click hooks: " + clickables.size());
        clickables.forEach((i) -> {System.out.println(i.toString());});
       	System.out.println("-------------------------");

       	/***** Future upgrades! Here more logic could be added for buttons, RoR dynamic content, ASP, etc... *****/

	} // end crawl





    public static boolean containsPattern(String string, String regex) {
        Pattern pattern = Pattern.compile(regex);

        // Check for the existence of the pattern
        Matcher matcher = pattern.matcher(string);
        return matcher.find();
    } // end containsPattern



} // end class MailX