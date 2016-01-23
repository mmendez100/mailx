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

import java.util.regex.Matcher;
import java.util.regex.Pattern;



/**
 * A simple scrapping for emails
 *
 * @author Manuel Mendez
 * @since January 22, 2016
 */
public class Mailx {

    static WebClient webClient;
    static String startPage;
    static String uri;
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_RESET = "\u001B[0m";
    
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
		//System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");    

       	// Now begin the drill down!
        try { 
        	webClient = new WebClient();
        	searchEmails(); }
        catch (Exception e) {
            e.printStackTrace();
        	System.err.println("Exiting...");
			System.exit(1);
        }
    } // end main


    public static boolean containsPattern(String string, String regex) {
        Pattern pattern = Pattern.compile(regex);

        // Check for the existence of the pattern
        Matcher matcher = pattern.matcher(string);
        return matcher.find();
    } // end containsPattern


	public static void searchEmails() throws Exception {
    	try { 

    		// We create our headless browser
    		final WebClient webClient = new WebClient();

    	}
  		catch (Exception e) {
            System.err.println("Could not open browser window! uri=" + uri);
            throw(e);
        }

	    // Suppress myriad of javascript errors, like real browsers do
    	webClient.getOptions().setThrowExceptionOnScriptError(false);
    		
    	// Connect and get the page
        System.out.println("Simulated browser opening " + uri);
		final HtmlPage page = webClient.getPage(startPage);

		// Some time for background javascript
        System.out.println("Simulated browser waiting for background JavaScript completion ");
    	webClient.waitForBackgroundJavaScript(1 * 1000);
	   	System.out.println("-------------------------");

	  	//Prepare to match on regexes for likely emails, very loose
       	//as some new domains 
       	Pattern emailP = Pattern.compile("[\\S^@]+@[\\S^@]+");

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

       	// We will follow only two kinds of links, absolute with our uri, or relative
       	// If we have been passed a page, we need to get the actual uri

       	Pattern absHref = Pattern.compile("(^.*)(href=\")(" + uri +"[^\"]+)(.*$)");
      	Pattern relHref = Pattern.compile("(^.*)(href=\")(/[^\"]+)(.*$)");

	    //get list of all anchors that contain either the uri or a local reference
	    Set<String> localAnchors = new HashSet<String>();
        final List<HtmlAnchor> allAnchors = page.getAnchors();
        System.out.println("total anchors found " + allAnchors.size());
        allAnchors.forEach((i) -> {
        	String iStr = i.toString();
        	System.out.println(iStr);
        	Matcher m1 = absHref.matcher(iStr);
        	Matcher m2 = relHref.matcher(iStr);
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
        	Matcher m1 = absHref.matcher(iStr);
        	Matcher m2 = relHref.matcher(iStr);
        	if (m1.matches()) { localLinks.add(m1.group(3)); m1.reset(); }
        	if (m2.matches()) { localLinks.add(m2.group(3)); m2.reset(); }
        });
        System.out.println("------ Links to Drill Into: -----");
        localLinks.forEach((i) -> { System.out.println(i); });
        System.out.println("------ Total unique local or relative links found: " + localLinks.size());
       	System.out.println("-------------------------");


       //get list of all elements that Angular uses for clickable dynamic links
        final List<?> divs = page.getByXPath("//@ng-click");
        System.out.println("elements w/ dynamic click hooks: " + divs.size());
        divs.forEach((i) -> {System.out.println(i.toString());});
       	System.out.println("-------------------------");

       	// More logic could be added for buttons, RoR dynamic content, ASP, etc...

	} // end searchEmails

} // end class MailX