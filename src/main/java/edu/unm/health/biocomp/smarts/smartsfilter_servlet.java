package edu.unm.health.biocomp.smarts;

import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import javax.servlet.*;
import javax.servlet.http.*;

import com.oreilly.servlet.*; //MultipartRequest, Base64Encoder, Base64Decoder
import com.oreilly.servlet.multipart.DefaultFileRenamePolicy;

import chemaxon.formats.*;
import chemaxon.util.MolHandler;
import chemaxon.struc.*; //MPropertyContainer
import chemaxon.struc.prop.MMoleculeProp;
import chemaxon.marvin.io.MPropHandler; //new way
import chemaxon.sss.search.*;
import chemaxon.reaction.*; //Standardizer, StandardizerException
import chemaxon.license.*; //LicenseManager

import edu.unm.health.biocomp.util.*;
import edu.unm.health.biocomp.util.http.*;
import edu.unm.health.biocomp.util.threads.*;

/**	SMARTS based filtering application.
	Note: Must add &lt;load-on-startup/&gt; to web.xml for smarts pre-load.
	@author Jeremy J Yang
*/
public class smartsfilter_servlet extends HttpServlet
{
  private static String SERVLETNAME=null;
  private static String CONTEXTPATH=null;
  private static String APPNAME=null; // configured in web.xml
  private static String UPLOADDIR=null; // configured in web.xml
  private static String SCRATCHDIR=null; // configured in web.xml
  private static Integer N_MAX=null;	// configured in web.xml
  private static Integer N_MAX_VIEW=null;	// configured in web.xml
  private static Integer MAX_POST_SIZE=null;	// configured in web.xml
  private static Boolean ENABLE_NOLIMIT=null;	// configured in web.xml
  private static ServletContext CONTEXT=null;
  private static String SMARTSDIR=null;
  //private static ServletConfig CONFIG=null;
  private static ResourceBundle rb=null;
  private static PrintWriter out=null;
  private static ArrayList<String> outputs=new ArrayList<String>();
  private static ArrayList<String> errors=new ArrayList<String>();
  private static HttpParams params=new HttpParams();
  //private static int SERVERPORT=0;
  private static String SERVERNAME=null;
  private static String REMOTEHOST=null;
  private static String DATESTR=null;
  private static String TMPFILE_PREFIX=null;
  private static int scratch_retire_sec=3600;
  private static final String[] STD_SMIRKSES={
    "[CX3:1](=[O:2])-[OX1:3]>>[C:1](=[O:2])-[O+0:3][H] carboxylic acid",
    "[NX3+:1](=[O:2])-[O-H0:3]>>[N+0:1](=[O+0:2])=[O+0:3] nitro(1)",
    "[NX4:1]([H])(=[O:2])-[O:3][H]>>[N+0:1](=[O+0:2])=[O+0:3] nitro(2)"};
  private static Standardizer stdizer=null;
  private static String[] glaxo_files={"glaxo_unsuitable_leads.sma","glaxo_unsuitable_natprod.sma","glaxo_reactive.sma"};
  private static String[] ursu_files={"unm_reactive.sma"};
  private static String[] alarmnmr_files={"alarmnmr.sma"};
  private static String[] blakelint_files={"lint_blake-v2.sma"};
  private static String[] oprea_files={"oprea_filters.sma"};
  private static String[] mlsmr_orig_files={"MLSMR_excluded.sma"};
  private static String[] mlsmr_files={"MLSMR_allowed.sma"};
  private static String[] toxic_files={"toxic.sma"};
  private static String[] pains_files={"pains_guha_t6.sma","pains_guha_t7.sma","pains_guha_t8.sma"};
  private static SmartsFile smartsFile=null;
  private LinkedHashMap<String,SmartsFile> SMARTSFILES=null; //Parsed once by init().
  private static String color1="#EEEEEE";
  private static String PROGRESS_WIN_NAME=null;
  private static boolean stdizer_islicensed=false;
  private MolImporter molReader=null;
  private static String SMI2IMG_SERVLETURL=null;
  private static String PROXY_PREFIX=null;	// configured in web.xml

  /////////////////////////////////////////////////////////////////////////////
  public void doPost(HttpServletRequest request,HttpServletResponse response) throws IOException,ServletException
  {
    //SERVERPORT = request.getServerPort();
    SERVERNAME = request.getServerName();
    if (SERVERNAME.equals("localhost")) SERVERNAME = InetAddress.getLocalHost().getHostAddress();
    REMOTEHOST = request.getHeader("X-Forwarded-For"); // client (original)
    if (REMOTEHOST!=null)
    {
      String[] addrs = Pattern.compile(",").split(REMOTEHOST);
      if (addrs.length>0) REMOTEHOST = addrs[addrs.length-1];
    }
    else
    {
      REMOTEHOST = request.getRemoteAddr(); // client (may be proxy)
    }

    CONTEXTPATH = request.getContextPath();
    rb = ResourceBundle.getBundle("LocalStrings", request.getLocale());

    MultipartRequest mrequest=null;
    if (request.getMethod().equalsIgnoreCase("POST"))
    {
      try { mrequest = new MultipartRequest(request, UPLOADDIR, MAX_POST_SIZE, "ISO-8859-1",
	new DefaultFileRenamePolicy()); }
      catch (IOException lEx) { this.getServletContext().log("not a valid MultipartRequest", lEx); }
    }

    // main logic:
    ArrayList<String> cssincludes = new ArrayList<String>(Arrays.asList(PROXY_PREFIX+CONTEXTPATH+"/css/biocomp.css"));
    ArrayList<String> jsincludes = new ArrayList<String>(Arrays.asList(PROXY_PREFIX+CONTEXTPATH+"/js/biocomp.js", PROXY_PREFIX+CONTEXTPATH+"/js/ddtip.js"));
    boolean ok=false;
    ok = initialize(request, mrequest);
    if (mrequest!=null)		//method=POST, normal operation
    {
      if (!ok)
      {
        response.setContentType("text/html");
        out = response.getWriter();
        out.print(HtmUtils.HeaderHtm(APPNAME, jsincludes, cssincludes, JavaScript(), "", color1, request));
        out.println(FormHtm(mrequest, response, params.getVal("formmode")));
        out.print(HtmUtils.FooterHtm(errors, true));
        return;
      }
      else if (mrequest.getParameter("changemode").equalsIgnoreCase("TRUE"))
      {
        response.setContentType("text/html");
        out = response.getWriter();
        out.print(HtmUtils.HeaderHtm(APPNAME, jsincludes, cssincludes, JavaScript(), "", color1, request));
        out.println(FormHtm(mrequest, response, params.getVal("formmode")));
        out.println("<SCRIPT LANGUAGE=\"JavaScript\">go_init(window.document.mainform,'"+params.getVal("formmode")+"',true)</SCRIPT>");
        out.print(HtmUtils.FooterHtm(errors, true));
      }
      else if (mrequest.getParameter("filter").equals("TRUE"))
      {
        if (mrequest.getParameter("runmode").equals("filter"))
        {
          response.setContentType("text/html");
          out = response.getWriter();
          out.print(HtmUtils.HeaderHtm(APPNAME, jsincludes, cssincludes, JavaScript(), "", color1, request));
          out.println(FormHtm(mrequest, response, params.getVal("formmode")));
          Date t_i = new Date();

          Vector<SmartsfilterResult> results = Smartsfilter_LaunchThread(this.molReader, mrequest, response, params);
          Smartsfilter_Results(results, this.molReader, mrequest, response, params);
          out.println("<SCRIPT>pwin.parent.focus(); pwin.focus(); pwin.close();</SCRIPT>");

          Date t_f = new Date();
          long t_d = t_f.getTime()-t_i.getTime();
          int t_d_min = (int)(t_d/60000L);
          int t_d_sec = (int)((t_d/1000L)%60L);
          errors.add(SERVLETNAME+": elapsed time: "+t_d_min+"m "+t_d_sec+"s");
          out.print(HtmUtils.OutputHtm(outputs));
          out.print(HtmUtils.FooterHtm(errors, true));
          HtmUtils.PurgeScratchDirs(Arrays.asList(SCRATCHDIR), scratch_retire_sec, false, ".", (HttpServlet) this);
        }
        else if (mrequest.getParameter("runmode").equals("analyze1mol"))
        {
          response.setContentType("text/html");
          out = response.getWriter();
          out.print(HtmUtils.HeaderHtm(APPNAME, jsincludes, cssincludes, JavaScript(), "", color1, request));
          out.println(FormHtm(mrequest, response, params.getVal("formmode")));
          Analyze1mol(this.molReader, params);
          out.print(HtmUtils.OutputHtm(outputs));
          out.print(HtmUtils.FooterHtm(errors, true));
        }
      }
    }
    else
    {
      
      String downloadtxt = request.getParameter("downloadtxt"); // POST param
      String downloadfile = request.getParameter("downloadfile"); // POST param
      if (request.getParameter("help")!=null)	// GET method, help=TRUE
      {
        response.setContentType("text/html");
        out = response.getWriter();
        out.print(HtmUtils.HeaderHtm(APPNAME, jsincludes, cssincludes, JavaScript(), "", color1, request));
        out.print(HelpHtm());
        out.print(HtmUtils.FooterHtm(errors, true));
      }
      else if (request.getParameter("test")!=null)	// GET method, test=TRUE
      {
        response.setContentType("text/plain");
        out = response.getWriter();
        HashMap<String,String> t = new HashMap<String,String>();
        t.put("JCHEM_LICENSE_OK", (LicenseManager.isLicensed(LicenseManager.JCHEM)?"True":"False"));
        t.put("JCHEM_MOLSEARCH_LICENSE_OK", (((new MolSearch()).isLicensed())?"True":"False"));
        out.print(HtmUtils.TestTxt(APPNAME, t));
      }
      else if (downloadtxt!=null && downloadtxt.length()>0) // POST param
      {
        ServletOutputStream ostream = response.getOutputStream();
        HtmUtils.DownloadString(response, ostream, downloadtxt, request.getParameter("fname"));
      }
      else if (downloadfile!=null && downloadfile.length()>0) // POST param
      {
        ServletOutputStream ostream = response.getOutputStream();
        HtmUtils.DownloadFile(response, ostream, downloadfile, request.getParameter("fname"));
      }
      else	// GET method, initial invocation of servlet
      {
        response.setContentType("text/html");
        out = response.getWriter();
        out.print(HtmUtils.HeaderHtm(APPNAME, jsincludes, cssincludes, JavaScript(), "", color1, request));
        out.println(FormHtm(mrequest, response, request.getParameter("formmode")));
        out.println("<SCRIPT>go_init(window.document.mainform,'"+request.getParameter("formmode")+"',false)</SCRIPT>");
        out.print(HtmUtils.FooterHtm(errors, true));
      }
    }
  }
  /////////////////////////////////////////////////////////////////////////////
  private boolean initialize(HttpServletRequest request, MultipartRequest mrequest) throws IOException,ServletException
  {
    SERVLETNAME = this.getServletName();
    PROGRESS_WIN_NAME = (SERVLETNAME+"_progress_win");
    params.clear();
    outputs.clear();
    errors.clear();
    SMI2IMG_SERVLETURL = (PROXY_PREFIX+CONTEXTPATH+"/mol2img");

    String logo_htm = "<TABLE CELLSPACING=5 CELLPADDING=5><TR><TD>";
    String imghtm = ("<IMG BORDER=0 SRC=\""+PROXY_PREFIX+CONTEXTPATH+"/images/biocomp_logo_only.gif\">");
    String tiphtm = (APPNAME+" web app from UNM Translational Informatics.");
    String href = ("http://medicine.unm.edu/informatics/");
    logo_htm+=(HtmUtils.HtmTipper(imghtm, tiphtm, href, 200, "white"));
    logo_htm+="</TD><TD>";
    imghtm = ("<IMG BORDER=0 SRC=\""+PROXY_PREFIX+CONTEXTPATH+"/images/chemaxon_powered_100px.png\">");
    tiphtm = ("JChem and Marvin from ChemAxon Ltd.");
    href = ("http://www.chemaxon.com");
    logo_htm+=(HtmUtils.HtmTipper(imghtm, tiphtm, href, 200, "white"));
    logo_htm+="</TD></TR></TABLE>";
    errors.add(logo_htm);

    Calendar calendar = Calendar.getInstance();
    calendar.setTime(new Date());
    DATESTR = String.format("%04d%02d%02d%02d%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH)+1, calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    Random rand = new Random();
    TMPFILE_PREFIX = SERVLETNAME+"."+DATESTR+"."+String.format("%03d",rand.nextInt(1000));

    try { LicenseManager.setLicenseFile(CONTEXT.getRealPath("")+"/.chemaxon/license.cxl"); }
    catch (Exception e) {
      errors.add("ERROR: "+e.getMessage());
      if (System.getenv("HOME") !=null) {
        try { LicenseManager.setLicenseFile(System.getenv("HOME")+"/.chemaxon/license.cxl"); }
        catch (Exception e2) {
          errors.add("ERROR: "+e2.getMessage());
        }
      }
    }
    LicenseManager.refresh();
    if (!LicenseManager.isLicensed(LicenseManager.JCHEM))
    {
      errors.add("ERROR: ChemAxon license error; JCHEM is required.");
      return false;
    }
    stdizer_islicensed = LicenseManager.isLicensed(LicenseManager.STANDARDIZER);
    if (!stdizer_islicensed) {
      errors.add("ChemAxon Standardizer license absent; disabling normalization.");
    }

    if (mrequest==null) return false;

    for (Enumeration e = mrequest.getParameterNames(); e.hasMoreElements(); )
    {
      String key = (String)e.nextElement();
      if (mrequest.getParameter(key)!=null)
        params.setVal(key, mrequest.getParameter(key));
    }

    if (params.isChecked("verbose"))
    {
      //errors.add("JChem version: "+chemaxon.jchem.version.VersionInfo.getVersion());
      errors.add("JChem version: "+com.chemaxon.version.VersionInfo.getVersion());
      errors.add("server: "+CONTEXT.getServerInfo()+" [API:"+CONTEXT.getMajorVersion()+"."+CONTEXT.getMinorVersion()+"]");

      for (String key:SMARTSFILES.keySet())
      {
        //String s="";
        //for (String groupname : sf.getGroupnames()) s+=(" "+groupname);
        SmartsFile sf=SMARTSFILES.get(key);
        errors.add(key+" ("+sf.getRawtxt().length()+" bytes, "
          +sf.size()+" smarts, "
          +sf.getDefines().size()+" defines, "
          +sf.getFailedsmarts().size()+" failed smarts)");
      }
    }

    if (mrequest.getParameter("changemode").equalsIgnoreCase("TRUE"))
    {
      return true;
    }

    /// Stuff for a run:

    Integer arom = (params.getVal("arom").equals("gen"))?MoleculeGraph.AROM_GENERAL:((params.getVal("arom").equals("bas"))?MoleculeGraph.AROM_BASIC:null);

    if (params.isChecked("nolimit"))
    {
      N_MAX=0;
      MAX_POST_SIZE = Integer.MAX_VALUE;
    }

    String fname="infile";
    File fileDB = mrequest.getFile(fname);
    String intxtDB = params.getVal("intxt").replaceFirst("[\\s]+$", "");
    int max_intxt_lines=5000;
    String line=null;
    if (fileDB!=null)
    {
      if (params.isChecked("file2txt") && fileDB!=null)
      {
        BufferedReader br = new BufferedReader(new FileReader(fileDB));
        intxtDB="";
        int i=0;
        for ( ;(line = br.readLine())!=null;++i)
        {
          intxtDB+=(line+"\n");
        }
        if (i>=max_intxt_lines)
        {
          errors.add("ERROR: input file too large, NOT copied to input (>="+max_intxt_lines+" lines)");
          params.setVal("intxt", "");
        }
        else
        {
          params.setVal("intxt", intxtDB);
        }
      }
      else
      {
        params.setVal("intxt", "");
      }
    }
    if (params.getVal("molfmt").equals("automatic"))
    {
      String orig_fname = mrequest.getOriginalFileName(fname);
      String ifmt_auto = MFileFormatUtil.getMostLikelyMolFormat(orig_fname);
      if (orig_fname!=null && ifmt_auto!=null)
      {
        if (fileDB!=null)
          this.molReader = new MolImporter(fileDB, ifmt_auto);
        else if (intxtDB.length()>0)
          this.molReader = new MolImporter(new ByteArrayInputStream(intxtDB.getBytes()), ifmt_auto);
      }
      else
      {
        if (fileDB!=null)
          this.molReader = new MolImporter(new FileInputStream(fileDB));
        else if (intxtDB.length()>0)
          this.molReader = new MolImporter(new ByteArrayInputStream(intxtDB.getBytes()));
      }
    }
    else
    {
      String ifmt = params.getVal("molfmt");
      if (fileDB!=null)
        this.molReader = new MolImporter(new FileInputStream(fileDB), ifmt);
      else if (intxtDB.length()>0)
        this.molReader = new MolImporter(new ByteArrayInputStream(intxtDB.getBytes()), ifmt);
    }
    if (this.molReader==null) { errors.add("ERROR: MolImporter==null."); return false; }
    int n_mol_estimate = EstimateNumRecords(this.molReader);
    if (N_MAX>0 && n_mol_estimate>N_MAX)
    {
      errors.add("Warning: estimated mol count &gt; N_MAX ("+n_mol_estimate+" > "+N_MAX+"); output may be truncated.");
    }
    if (params.isChecked("verbose"))
    {
      String ifmt = this.molReader.getFormat();
      MFileFormat mffmt = MFileFormatUtil.getFormat(ifmt); //null if ifmt "SDF:V3" (JChem 6.3.1 bug?)
      errors.add("input format:  "+ifmt+((mffmt!=null)?(" ("+mffmt.getDescription()+")"):""));
    }

    ArrayList<String> smartsfnames = new ArrayList<String>();
    if (params.isChecked("glaxo"))
      smartsfnames.addAll(Arrays.asList(glaxo_files));
    if (params.isChecked("ursu"))
      smartsfnames.addAll(Arrays.asList(ursu_files));
    if (params.isChecked("alarmnmr"))
      smartsfnames.addAll(Arrays.asList(alarmnmr_files));
    if (params.isChecked("blakelint"))
      smartsfnames.addAll(Arrays.asList(blakelint_files));
    if (params.isChecked("oprea"))
      smartsfnames.addAll(Arrays.asList(oprea_files));
    if (params.isChecked("mlsmr_orig"))
      smartsfnames.addAll(Arrays.asList(mlsmr_orig_files));
    if (params.isChecked("mlsmr"))
      smartsfnames.addAll(Arrays.asList(mlsmr_files));
    if (params.isChecked("toxic"))
      smartsfnames.addAll(Arrays.asList(toxic_files));
    if (params.isChecked("pains"))
      smartsfnames.addAll(Arrays.asList(pains_files));

    File file_sma = mrequest.getFile("infile_sma");
    String intxt_sma = params.getVal("intxt_sma").replaceFirst("[\\s]+$", "");

    smartsFile = new SmartsFile();	// the accumulator
    if (smartsfnames.size()>0)	// canned files
    {
      for (int i=0;i<smartsfnames.size();++i)
      {
        String smartsfname = smartsfnames.get(i);
        SmartsFile sf = SMARTSFILES.get(smartsfname);
        if (sf==null)
        {
          errors.add("ERROR: smartsfile not loaded: "+smartsfname);
          return false;
        }
        if (sf.size()==0)
        {
          errors.add("ERROR: smartsfile loaded but empty: "+smartsfname);
          return false;
        }
        try {
          smartsFile.mergeFiles(sf);
          if (params.isChecked("verbose"))
            errors.add("smarts file: "+smartsfname+" ("+sf.size()+" smarts)");
        }
        catch (Exception e) {
          errors.add("Exception: "+e.getMessage());
          return false;
        }
      }
    }
    if (file_sma!=null)	// custom: uploaded file
    {
      String orig_smarts_fname = mrequest.getOriginalFileName("infile_sma");
      try {
        SmartsFile sf = new SmartsFile();
        sf.parseFile(file_sma, params.isChecked("strict"), orig_smarts_fname);
        smartsFile.mergeFiles(sf);
      }
      catch (Exception e) {
        errors.add("Exception: "+e.getMessage());
        return false;
      }
      params.setVal("intxt_sma", smartsFile.getRawtxt());
    }
    else if (intxt_sma.length()>0)	// custom: pasted file
    {
      try {
        SmartsFile sf = new SmartsFile();
        sf.parseFile(intxt_sma, params.isChecked("strict"), "custom");
        smartsFile.mergeFiles(sf);
      }
      catch (Exception e) {
        errors.add("Exception: "+e.getMessage());
        return false;
      }
    }
    if (smartsFile.size()==0)
    {
      errors.add("No input smarts.");
      return false;
    }

    if (stdizer_islicensed)
    {
      String xml="";
      String name="";
      String smirks="";
      for (int i=0;i<STD_SMIRKSES.length;++i)
      {
        smirks = STD_SMIRKSES[i];
        if (smirks.indexOf(" ")>0)
        {
          name = smirks.substring(smirks.indexOf(" ")+1);
          smirks = smirks.replace("\\s.*$", "");
        }
        else
        {
          name = String.format("%d", i);
        }
        xml+="<Transformation ID=\""+name+"\" Structure=\""+smirks+"\"/>";
      }
      xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><StandardizerConfiguration Version =\"0.1\"><Actions>"+
        xml+"</Actions></StandardizerConfiguration>");
      try { stdizer = new Standardizer(xml); }
      catch (StandardizerException e) { errors.add("StandardizerException: "+e.getMessage()); }
    }

    errors.add("smarts loaded: "+smartsFile.size());
    errors.add("smarts defines read: "+smartsFile.getDefines().size());
    for (int i=0;i<smartsFile.getFailedsmarts().size();++i)
    {
      errors.add("problem parsing smarts: "+smartsFile.getFailedsmarts().get(i));
    }
    return true;
  }
  /////////////////////////////////////////////////////////////////////////////
  private static int EstimateNumRecords(MolImporter molReader)
	throws IOException
  {
    molReader.setThreadCount(1);
    long pos = molReader.tell();
    int n_est=0;
    Molecule mol;
    if (molReader.isRewindable())
    {
      for (int i=0;i<100;++i)
      {
        try { mol = molReader.read(); }
        catch (MolFormatException e) { continue; }
        //catch (IOException e) { continue; }
        if (mol==null) break;
      }
      n_est = molReader.estimateNumRecords();
      try { molReader.seekRecord((int)pos, null); }
      catch (Exception e) { errors.add("Warning: (EstimateNumRecords): "+e.getMessage()); }
    }
    return n_est;
  }
  /////////////////////////////////////////////////////////////////////////////
  private static MolImporter RewindMolReader(MolImporter molReader, MultipartRequest mrequest, HttpParams params) throws IOException
  {
    if (molReader.isRewindable())
    {
      molReader.seekRecord(0, null);
    }
    else
    {
      String ifmt = molReader.getFormat();
      molReader.close();
      String fname="infile";
      File fileDB = mrequest.getFile(fname);
      String intxtDB = params.getVal("intxt").replaceFirst("[\\s]+$", "");
      MolImporter molReader_new = (fileDB!=null)?(new MolImporter(fileDB, ifmt)):((intxtDB.length()>0)?(new MolImporter(new ByteArrayInputStream(intxtDB.getBytes()), ifmt)):null);
      molReader = molReader_new;
    }
    return molReader;
  }
  /////////////////////////////////////////////////////////////////////////////
  private static String FormHtm(MultipartRequest mrequest, HttpServletResponse response, String formmode) throws IOException,ServletException
  {
    if (formmode==null) formmode="normal";
    String formmode_normal=""; String formmode_expert="";
    if (formmode.equals("expert")) formmode_expert="CHECKED";
    else if (formmode.equals("normal")) formmode_normal="CHECKED";
    else formmode_normal="CHECKED";

    String molfmt_menu = "<SELECT NAME=\"molfmt\">\n";
    molfmt_menu+=("<OPTION VALUE=\"automatic\">automatic\n");
    for (String fmt: MFileFormatUtil.getMolfileFormats())
    {
      String desc = MFileFormatUtil.getFormat(fmt).getDescription();
      molfmt_menu+=("<OPTION VALUE=\""+fmt+"\">"+desc+"\n");
    }
    molfmt_menu+=("</SELECT>");
    molfmt_menu = molfmt_menu.replace("\""+params.getVal("molfmt")+"\">", "\""+params.getVal("molfmt")+"\" SELECTED>");

    String arom_gen=""; String arom_bas=""; String arom_none="";
    if (params.getVal("arom").equals("gen")) arom_gen="CHECKED";
    else if (params.getVal("arom").equals("bas")) arom_bas="CHECKED";
    else arom_none="CHECKED";

    String runmode_analyze1mol=""; String runmode_filter="";
    if (params.getVal("runmode").equals("analyze1mol")) runmode_analyze1mol="CHECKED";
    else runmode_filter="CHECKED";

    String ofmt_smiles=""; String ofmt_sdf="";
    if (params.getVal("ofmt").equals("sdf")) ofmt_sdf="CHECKED";
    else ofmt_smiles="CHECKED";

    String htm = (
       ("<FORM NAME=\"mainform\" METHOD=POST\n")
      +(" ACTION=\""+response.encodeURL(SERVLETNAME)+"\"\n")
      +(" ENCTYPE=\"multipart/form-data\">\n")
      +("<INPUT TYPE=HIDDEN NAME=\"filter\">\n")
      +("<INPUT TYPE=HIDDEN NAME=\"changemode\">\n")
      +("<TABLE WIDTH=\"100%\"><TR><TD><H1>"+APPNAME+"</H1></TD>\n")
      +("<TD WIDTH=\"30%\" ALIGN=RIGHT>\n")
      +("<B>mode:</B>\n")
      +("<INPUT TYPE=RADIO NAME=\"formmode\" VALUE=\"normal\" onClick=\"go_changemode(document.mainform)\" "+formmode_normal+">normal\n")
      +("<INPUT TYPE=RADIO NAME=\"formmode\" VALUE=\"expert\" onClick=\"go_changemode(document.mainform)\" "+formmode_expert+">expert\n")
      +("<BUTTON TYPE=BUTTON onClick=\"void window.open('"+response.encodeURL(SERVLETNAME)+"?help=TRUE','helpwin','width=600,height=400,scrollbars=1,resizable=1')\"><B>Help</B></BUTTON>\n")
      +("<BUTTON TYPE=BUTTON onClick=\"go_demo(this.form, 'normal')\"><B>Demo</B></BUTTON>\n")
      +("<BUTTON TYPE=BUTTON onClick=\"window.location.replace('"+response.encodeURL(SERVLETNAME)+"?formmode="+formmode+"')\"><B>Reset</B></BUTTON>\n")
      +("</TD></TR></TABLE>\n")
      +("<HR>\n")
      +("<TABLE WIDTH=\"100%\" CELLPADDING=5 CELLSPACING=5>\n")
      +("<TR BGCOLOR=\"#CCCCCC\"><TD VALIGN=TOP>\n")
      +("<B>input:</B> fmt:"+molfmt_menu)
      +("<INPUT TYPE=CHECKBOX NAME=\"file2txt\" VALUE=\"CHECKED\" "+params.getVal("file2txt")+">file2txt<BR>\n")
      +("upload: <INPUT TYPE=\"FILE\" NAME=\"infile\"> ...or paste:\n")
      +("<BR><TEXTAREA NAME=\"intxt\" WRAP=OFF ROWS=12 COLS=48>"+params.getVal("intxt")+"</TEXTAREA>\n")
      +("</TD>\n")
      +("<TD WIDTH=\"30%\" VALIGN=TOP>\n")
      +("<B>smarts:</B> (select any combo):\n")
      +("<TABLE WIDTH=\"100%\"><TR><TD WIDTH=\"50%\" VALIGN=TOP>")
      +("<INPUT TYPE=CHECKBOX NAME=\"blakelint\" VALUE=\"CHECKED\" "+params.getVal("blakelint")+">Blake<BR>\n")
      +("<INPUT TYPE=CHECKBOX NAME=\"glaxo\" VALUE=\"CHECKED\" "+params.getVal("glaxo")+">Glaxo<BR>\n")
      +("<INPUT TYPE=CHECKBOX NAME=\"pains\" VALUE=\"CHECKED\" "+params.getVal("pains")+">PAINS<BR>\n")
      +("</TD><TD VALIGN=TOP>\n")
      +("<INPUT TYPE=CHECKBOX NAME=\"alarmnmr\" VALUE=\"CHECKED\" "+params.getVal("alarmnmr")+">ALARM NMR<BR>\n")
      +("<INPUT TYPE=CHECKBOX NAME=\"oprea\" VALUE=\"CHECKED\" "+params.getVal("oprea")+">Oprea<BR>\n")
      +("</TD></TR></TABLE>\n"));
    if (formmode.equals("expert"))
    {
      htm+=(("<TABLE WIDTH=\"100%\"><TR><TD WIDTH=\"50%\" VALIGN=TOP>")
        +("<INPUT TYPE=CHECKBOX NAME=\"mlsmr_orig\" VALUE=\"CHECKED\" "+params.getVal("mlsmr_orig")+">MLSMR-original<BR>\n")
        +("<INPUT TYPE=CHECKBOX NAME=\"mlsmr\" VALUE=\"CHECKED\" "+params.getVal("mlsmr")+">MLSMR-relaxed<BR>\n")
        +("</TD><TD VALIGN=TOP>\n")
        +("<INPUT TYPE=CHECKBOX NAME=\"ursu\" VALUE=\"CHECKED\" "+params.getVal("ursu")+">Ursu-reactive<BR>\n")
        +("<INPUT TYPE=CHECKBOX NAME=\"toxic\" VALUE=\"CHECKED\" "+params.getVal("toxic")+">toxicity<BR>\n")
        +("</TD></TR></TABLE>\n")
        +("... or upload: <INPUT TYPE=\"FILE\" NAME=\"infile_sma\"> ...or paste:\n")
        +("<BR><TEXTAREA NAME=\"intxt_sma\" WRAP=OFF ROWS=8 COLS=40>"+params.getVal("intxt_sma")+"</TEXTAREA>\n"));
    }
    htm+=(("</TD>\n")
      +("<TD VALIGN=TOP>\n")
      +("<B>runmode:</B><BR>\n")
      +("<INPUT TYPE=RADIO NAME=\"runmode\" VALUE=\"filter\" "+runmode_filter+">filter\n")
      +("&nbsp;<INPUT TYPE=RADIO NAME=\"runmode\" VALUE=\"analyze1mol\" "+runmode_analyze1mol+">analyze1mol<BR>\n")
      +("<HR>\n<B>output:</B><BR>\n")
      +("<INPUT TYPE=CHECKBOX NAME=\"out_batch\" VALUE=\"CHECKED\" "+params.getVal("out_batch")+">batch &nbsp; fmt:")
      +("<INPUT TYPE=RADIO NAME=\"ofmt\" VALUE=\"smiles\" "+ofmt_smiles+">smiles\n")
      +("&nbsp;<INPUT TYPE=RADIO NAME=\"ofmt\" VALUE=\"sdf\" "+ofmt_sdf+">sdf<BR>\n")
      +("<INPUT TYPE=CHECKBOX NAME=\"out_view\" VALUE=\"CHECKED\" "+params.getVal("out_view")+">view ")
      +("&nbsp; <INPUT TYPE=CHECKBOX NAME=\"depict\" VALUE=\"CHECKED\" "+params.getVal("depict")+">depict<BR>\n"));
    if (formmode.equals("expert"))
    {
      htm+=(("&nbsp; <INPUT TYPE=CHECKBOX NAME=\"showmatches\" VALUE=\"CHECKED\" "+params.getVal("showmatches")+">show matches<BR>\n")
        +("<INPUT TYPE=CHECKBOX NAME=\"inc_pass\" VALUE=\"CHECKED\" "+params.getVal("inc_pass")+">include passes<BR>\n")
        +("<INPUT TYPE=CHECKBOX NAME=\"inc_fail\" VALUE=\"CHECKED\" "+params.getVal("inc_fail")+">include fails<BR>\n")
        +("<INPUT TYPE=CHECKBOX NAME=\"fullann\" VALUE=\"CHECKED\" "+params.getVal("fullann")+">full annotation<BR>\n"));
    }
    else
    {
      htm+=("<INPUT TYPE=HIDDEN NAME=\"showmatches\" VALUE=\"CHECKED\">")
        +("<INPUT TYPE=HIDDEN NAME=\"inc_pass\" VALUE=\"CHECKED\">")
        +("<INPUT TYPE=HIDDEN NAME=\"fullann\" VALUE=\"CHECKED\">");
    }
    htm+=("<HR>\n<B>misc:</B><BR>\n");
    if (formmode.equals("expert"))
    {
      htm+=("arom:<INPUT TYPE=RADIO NAME=\"arom\" VALUE=\"gen\" "+arom_gen+">gen\n")
        +("&nbsp;<INPUT TYPE=RADIO NAME=\"arom\" VALUE=\"bas\" "+arom_bas+">bas\n")
        +("&nbsp;<INPUT TYPE=RADIO NAME=\"arom\" VALUE=\"none\" "+arom_none+">none<BR>\n");
      if (stdizer_islicensed)
        htm+=("<INPUT TYPE=CHECKBOX NAME=\"fixmols\" VALUE=\"CHECKED\" "+params.getVal("fixmols")+">normalize mols<BR>\n");
      htm+=("<INPUT TYPE=CHECKBOX NAME=\"strict\" VALUE=\"CHECKED\" "+params.getVal("strict")+">strict uploaded smarts parsing<BR>\n");
    }
    else
    {
      if (stdizer_islicensed)
        htm+=("<INPUT TYPE=HIDDEN NAME=\"fixmols\" VALUE=\"CHECKED\">\n");
      htm+=("<INPUT TYPE=HIDDEN NAME=\"arom\" VALUE=\"gen\">\n");
    }
    if (formmode.equals("expert") && ENABLE_NOLIMIT)
      htm+=("<INPUT TYPE=CHECKBOX NAME=\"nolimit\" VALUE=\"CHECKED\" "+params.getVal("nolimit")+">no-limit, input size <I>(default: N_MAX="+N_MAX+")</I><BR>\n");
    else
      htm+=("<INPUT TYPE=HIDDEN NAME=\"nolimit\" VALUE=\"\">\n");
    htm+=("<INPUT TYPE=CHECKBOX NAME=\"verbose\" VALUE=\"CHECKED\" "+params.getVal("verbose")+">verbose<BR>\n")
      +("</TD></TR></TABLE>\n")
      +("<P>\n")
      +("<CENTER>\n")
      +("<BUTTON TYPE=BUTTON onClick=\"go_smartsfilter(this.form,'"+formmode+"')\"><B>Go "+APPNAME+"</B></BUTTON>\n")
      +("</CENTER>\n")
      +("</FORM>\n");
    return htm;
  }
  /////////////////////////////////////////////////////////////////////////////
  private static void Analyze1mol(MolImporter molReader, HttpParams params) throws IOException, ServletException
  {
    int w=240;
    int h=180;
    String depopts = ("mode=cow");
    depopts+=("&imgfmt=png");
    if (params.getVal("arom").equals("gen")) depopts+=("&arom_gen=true");
    else if (params.getVal("arom").equals("bas")) depopts+=("&arom_bas=true");
    else if (params.getVal("arom").equals("none")) depopts+=("&kekule=true");

    Molecule mol=null;
    try { mol = molReader.read(); }
    catch (MolFormatException e) {
      errors.add("ERROR: "+e.getMessage());
      return;
    }

    Integer arom = (params.getVal("arom").equals("gen"))?MoleculeGraph.AROM_GENERAL:((params.getVal("arom").equals("bas"))?MoleculeGraph.AROM_BASIC:null);

    if (arom!=null)
      mol.aromatize(arom);
    else
      mol.dearomatize();

    String smiles = MolExporter.exportToFormat(mol, "smiles:");
    String origsmiles=smiles;
    if (params.isChecked("fixmols"))
    {
      try { stdizer.standardize(mol); }
      catch (StandardizerException e)
      { errors.add("StandardizerException:"+e.getMessage()); }
      catch (SearchException e)
      { errors.add("SearchException: :"+e.getMessage()); }
      smiles = MolExporter.exportToFormat(mol, "smiles:");
      if (params.isChecked("verbose"))
      {
        errors.add("normalization: "+origsmiles+" &gt;&gt; "+smiles);
      }
    }

    String imghtm = HtmUtils.Smi2ImgHtm(smiles, depopts, h, w, SMI2IMG_SERVLETURL, true, 4, "go_zoom_smi2img");

    outputs.add("molecule: <B>"+mol.getName()+"</B>");
    if (params.isChecked("depict"))
      outputs.add(imghtm+"\n");

    String thtm = ("<TABLE BORDER>\n");
    thtm+=("<TR><TH>&nbsp;</TH><TH>match</TH><TH>pattern name</TH><TH>group</TH></TR>\n");
    int n_matches=0;
    for (int i=0;i<smartsFile.size();++i)
    {
      Smarts smrt = smartsFile.getSmarts(i);
      boolean hit=false;
      try {
        smrt.getSearch().setTarget(mol);
        hit = smrt.getSearch().isMatching();
      }
      catch (SearchException e) {
        errors.add("SearchException: "+e);
        continue;
      }
      catch (Exception e) {
        errors.add(e.getMessage());
        continue;
      }
      if (hit)
        thtm+="<TR BGCOLOR=\"#FF8888\">";
      else
        thtm+="<TR>";
      thtm+="<TD VALIGN=TOP>"+(i+1)+". </TD>\n";
      String smahtm = ("<TT>"+smrt.getRawsmarts()+"</TT>");
      if (!smrt.getSmarts().equals(smrt.getRawsmarts()))
        smahtm+=("<BR><I>"+smrt.getSmarts()+"</I>");
      if (hit)
      {
        String opts = (depopts+"&smartscode="+URLEncoder.encode(smrt.getSmarts(), "UTF-8"));
        imghtm = HtmUtils.Smi2ImgHtm(smiles, opts, h, w, SMI2IMG_SERVLETURL, false, 4, null);
        thtm+=("<TD ALIGN=CENTER VALIGN=TOP><TT>"+HtmUtils.HtmTipper("<B>yes</B>", imghtm, w, "white")+"</TT></TD>\n");
        ++n_matches;
      }
      else
      {
        thtm+="<TD ALIGN=CENTER VALIGN=TOP><I>no</I></TD>\n";
      }
      thtm+=("<TD VALIGN=TOP>"+HtmUtils.HtmTipper(smrt.getName(), smahtm, 200, "yellow")+"</TD>\n");
      thtm+=("<TD>"+smrt.getGroupname()+"</TD>\n");
      thtm+="</TD></TR>\n";
    }
    thtm+=("</TABLE>");

    if (n_matches==0)
      outputs.add("<B>RESULT:</B> pass");
    else
      outputs.add("<B>RESULT:</B> fail");
    outputs.add("N_smarts = "+smartsFile.size());
    outputs.add("matches = "+n_matches);

    outputs.add("<B>smarts patterns:</B>");
    outputs.add(thtm);
  }
  /////////////////////////////////////////////////////////////////////////////
  private static Vector<SmartsfilterResult> Smartsfilter_LaunchThread(MolImporter molReader, MultipartRequest mrequest,HttpServletResponse response,HttpParams params) throws IOException,ServletException
  {
    ExecutorService exec = Executors.newSingleThreadExecutor();
    int tpoll=5000; //msec
    Integer arom = (params.getVal("arom").equals("gen"))?MoleculeGraph.AROM_GENERAL:((params.getVal("arom").equals("bas"))?MoleculeGraph.AROM_BASIC:null);
    Smartsfilter_Task task = new Smartsfilter_Task(molReader, smartsFile, arom, stdizer, N_MAX);
    TaskUtils.ExecTaskWeb(exec, task, task.taskstatus, SERVLETNAME, tpoll, out, response, PROGRESS_WIN_NAME);
    if (task.getErrorCount()>0)
    {
      errors.add("Smartsfilter_Task errors: "+task.getErrorCount());
      for (String errtxt: task.getErrors())
        errors.add(errtxt);
    }
    return task.getResults();
  }
  /////////////////////////////////////////////////////////////////////////////
  private static void Smartsfilter_Results(Vector<SmartsfilterResult> results, MolImporter molReader, MultipartRequest mrequest,HttpServletResponse response, HttpParams params)
	throws IOException,ServletException
  {
    int w=120;
    int h=120;
    String depopts = ("mode=cow");
    depopts+=("&imgfmt=png");
    if (params.getVal("arom").equals("gen")) depopts+=("&arom_gen=true");
    else if (params.getVal("arom").equals("bas")) depopts+=("&arom_bas=true");
    else if (params.getVal("arom").equals("none")) depopts+=("&kekule=true");

    File fout=null;
    try {
      File dout = new File(SCRATCHDIR);
      if (!dout.exists())
      {
        boolean ok = dout.mkdir();
        System.err.println("SCRATCHDIR creation "+(ok?"succeeded":"failed")+": "+SCRATCHDIR);
      }
      fout = File.createTempFile(TMPFILE_PREFIX, "_out."+params.getVal("ofmt"), dout);
    }
    catch (IOException e) {
      errors.add("ERROR: could not open temp file; check SCRATCHDIR: "+SCRATCHDIR);
      return;
    }
    String ofmt_full = params.getVal("ofmt")+":";
    if (params.getVal("ofmt").equals("smiles"))
      ofmt_full+="r1";  //no-checking, as-is
    if (params.getVal("ofmt").equals("smiles") && params.isChecked("fullann"))
    {
      ofmt_full+="T";  //header line
      for (String groupname : smartsFile.getGroupnames()) { ofmt_full+=(groupname+":"); }
    }

    MolExporter molWriter = new MolExporter(new FileOutputStream(fout), ofmt_full);

    LinkedHashMap<String,Integer> group_stats = new LinkedHashMap<String,Integer>();
    for (String groupname : smartsFile.getGroupnames()) group_stats.put(groupname, 0);

    // Can we simply rewind the molReader and merge SD data?
    // Yes.  But I how robust is this regarding syncronization?
    MolImporter molReader2 = RewindMolReader(molReader, mrequest, params);

    int n_pass=0;
    int n_fail=0;
    int n_err=0;
    for (int i=0;i<results.size();++i)
    {
      SmartsfilterResult result = results.get(i);
      Molecule mol; //from results
      String smi = result.getSmiles();
      if (smi==null || smi.isEmpty())
      {
        mol = new Molecule(); //empty
      }
      else
      {
        try { mol = MolImporter.importMol(smi, "smiles:"); }
        catch (MolFormatException e) { errors.add("["+(i+1)+"] "+e.getMessage()); ++n_err; continue; }
      }

      Molecule mol_in; //from input file
      try { mol_in = molReader2.read(); }
      catch (MolFormatException e) { errors.add("["+(i+1)+"] "+e.getMessage()); ++n_err; continue; }

      if (mol==null) { ++n_err; continue; }
      if (mol_in==null) { ++n_err; continue; }

      if (params.isChecked("fullann"))
      {
        mol.setProperty("SMARTSFILTER_MOLNAME", result.getName());

        MPropertyContainer mprops_in = mol_in.properties();
        for (String key: mprops_in.getKeys())
          mol.setProperty(key, MPropHandler.convertToString(mprops_in, key));

        for (String groupname:smartsFile.getGroupnames())
          mol.setProperty(groupname, "pass"); //overwritten by fails
      }
      if (result.getMatches().size()>0)
      {
        if (params.isChecked("fullann"))
        {
          mol.setProperty("SMARTSFILTER_MATCHES", "");
          for (int j=0;j<result.getMatches().size();++j)
          {
            Smarts match = result.getMatches().get(j);
            mol.setProperty(match.getGroupname(), "fail");
            mol.setProperty("SMARTSFILTER_MATCHES",
              //mol.getProperty("SMARTSFILTER_MATCHES")+((j>0)?"\n":"")+match.getSmarts()+" //old way
              //"+match.getGroupname()+": "+match.getName()); //old way
              MPropHandler.convertToString(mol.properties(), "SMARTSFILTER_MATCHES")+((j>0)?"\n":"")
                +match.getSmarts()+" "+match.getGroupname()+": "+match.getName()); //new way
          }
        }
        else
        {
          mol.setName(mol.getName()+"\tfail");
        }
        //ArrayList<String> groupnames_this = new ArrayList<String>();
        //for (Smarts match:result.getMatches())
        //{
        //  if (!groupnames_this.contains(match.getGroupname())) groupnames_this.add(match.getGroupname());
        //}
        //for (String groupname:groupnames_this) { group_stats.put(groupname,group_stats.get(groupname)+1); }
        for (String groupname:smartsFile.getGroupnames())
        {
          if (result.groupHasMatch(groupname)) group_stats.put(groupname, group_stats.get(groupname)+1);
        }
        ++n_fail;
        if (params.isChecked("inc_fail"))
        {
          molWriter.write(mol);
        }
      }
      else
      {
        ++n_pass;
        if (params.isChecked("inc_pass"))
        {
          molWriter.write(mol);
        }
      }
    }

    int n_mols = results.size();
    outputs.add("<B>RESULT:</B>");
    outputs.add("total: "+n_mols);
    if (n_mols==N_MAX) outputs.add("Warning: N_MAX limit reached; output may be truncated.");
    outputs.add(String.format("passed: %d (%.1f%%)", n_pass, 100.0*n_pass/n_mols));
    outputs.add(String.format("failed: %d (%.1f%%)", n_fail, 100.0*n_fail/n_mols));
    outputs.add(String.format("errors: %d", n_err));

    for (String groupname:group_stats.keySet())
    {
      int n_fail_group = group_stats.get(groupname);
      outputs.add(String.format("failed %s: %d (%.1f%%)", groupname, n_fail_group, 100.0*n_fail_group/n_mols));
    }

    int n_out=0;
    if (n_pass>0 && params.isChecked("inc_pass")) n_out+=n_pass;
    if (n_fail>0 && params.isChecked("inc_fail")) n_out+=n_fail;

    if (n_out>0 && params.isChecked("out_batch"))
    {
      outputs.add(String.format("out: %d", n_out));
      String fpath = fout.getAbsolutePath();
      long fsize = fout.length();
      String fname = (SERVLETNAME+"_out."+params.getVal("ofmt"));
      outputs.add(
        "<FORM METHOD=\"POST\" ACTION=\""+response.encodeURL(SERVLETNAME)+"\">\n"+
        "<INPUT TYPE=HIDDEN NAME=\"downloadfile\" VALUE=\""+fpath+"\">\n"+
        "<INPUT TYPE=HIDDEN NAME=\"fname\" VALUE=\""+fname+"\">\n"+
        "<BUTTON TYPE=BUTTON onClick=\"this.form.submit()\">"+
        "download "+fname+" ("+file_utils.NiceBytes(fsize)+")</BUTTON></FORM>\n");
    }

    String thtm="";
    if (params.isChecked("out_view"))
    {
      thtm+=("<TABLE BORDER>\n");
      thtm+=("<TR><TH>&nbsp;</TH>\n");
      if (params.isChecked("depict")) thtm+=("<TH>mol</TH>\n");
      thtm+=("<TH>molname</TH><TH>result</TH>\n");
      if (params.isChecked("showmatches") && params.isChecked("inc_fail"))
        thtm+=("<TH>matches</TH>\n");
      if (params.isChecked("fullann"))
      {
        for (String groupname : smartsFile.getGroupnames())
          thtm+=("<TH>"+groupname+"</TH>\n");
      }
      thtm+=("</TR>\n");
      int n_view=0;
      for (int i=0;i<results.size();++i)
      {
        SmartsfilterResult result = results.get(i);
        //ArrayList<Smarts> matches = result.getMatches();
        String smiles = result.getSmiles();
        String molname = result.getName();
        if (molname.isEmpty()) molname = String.format("%d", result.getIndex()+1);
        String opts=depopts;
        String rhtm="";
        if (result.getMatches().size()>0)
        {
          // problem: huge smarts lost via command line
          if (!params.isChecked("inc_fail")) continue;
          String smartses="";
          for (int j=0;j<result.getMatches().size();++j)
          {
            if (j>0) smartses+="\t";
            smartses+=result.getMatches().get(j).getSmarts();
          }
          if (smartses.length()<2000)
            opts+="&smartses="+URLEncoder.encode(smartses, "UTF-8");
          rhtm+="<TR BGCOLOR=\"#FF8888\">";
        }
        else
        {
          if (!params.isChecked("inc_pass")) continue;
          rhtm+="<TR BGCOLOR=\"#88FF88\">";
        }
        ++n_view;
        rhtm+="<TD VALIGN=TOP>"+(n_view)+". </TD>";
        String imghtm;
        if (params.isChecked("depict"))
        {
          imghtm = HtmUtils.Smi2ImgHtm(smiles, opts, h, w, SMI2IMG_SERVLETURL, true, 4, "go_zoom_smi2img");
          rhtm+="<TD>"+imghtm+"</TD>\n";
          rhtm+="<TD><TT>"+molname+"</TT></TD>\n";
        }
        else
        {
          imghtm = HtmUtils.Smi2ImgHtm(smiles, opts, h, w, SMI2IMG_SERVLETURL, false, 4, null);
          rhtm+=("<TD><TT>"+HtmUtils.HtmTipper(molname, imghtm, w, "white")+"</TT></TD>\n");
        }

        rhtm+="<TD>"+(result.pass()?"pass":"fail")+"</TD>\n";
        if (params.isChecked("showmatches") && params.isChecked("inc_fail"))
        {
          String matchhtm="";
          if (result.getMatches().size()>0)
          {
            matchhtm=("<UL>\n");
            for (int j=0;j<result.getMatches().size();++j)
            {
              Smarts smrt = result.getMatches().get(j);
              matchhtm+=("<LI>");
              String smahtm = ("<TT>"+smrt.getRawsmarts()+"</TT>");
              if (!smrt.getSmarts().equals(smrt.getRawsmarts()))
                smahtm+=("<BR><I>"+smrt.getSmarts()+"</I>");
              matchhtm+=(HtmUtils.HtmTipper(smrt.getGroupname()+":"+smrt.getName(), smahtm, 200, "yellow")+"\n");
            }
            matchhtm+=("</UL>\n");
            rhtm+=("<TD>"+matchhtm+"</TD>\n");
          }
          else
          {
            rhtm+=("<TD>&nbsp;</TD>\n");
          }
        }
        if (params.isChecked("fullann"))
        {
          for (String groupname : smartsFile.getGroupnames())
          {
            if (result.groupHasMatch(groupname))
              rhtm+=("<TD>fail</TD>\n");
            else
              rhtm+=("<TD BGCOLOR=\"#88FF88\">pass</TD>\n");
          }
        }
        rhtm+="</TR>\n";
        if (n_view<=N_MAX_VIEW)
          thtm+=(rhtm+"\n");
      }
      thtm+="</TABLE>\n";
      if (n_out>0) outputs.add(thtm);
      if (n_out>N_MAX_VIEW)
        outputs.add("View truncated at N_MAX_VIEW mols: "+N_MAX_VIEW);
    }
  }
  /////////////////////////////////////////////////////////////////////////////
  private static String JavaScript()
  {
    String DEMO_DATASET = (
"CN1C(=O)N(C)C(=O)C(N(C)C=N2)=C12 caffeine\n"+
"COc1cc2c(ccnc2cc1)C(O)C4CC(CC3)C(C=C)CN34 quinine\n"+
"CC1(C)SC2C(NC(=O)Cc3ccccc3)C(=O)N2C1C(=O)O benzylpenicillin\n"+
"CCC(=C(c1ccc(OCCN(C)C)cc1)c1ccccc1)c1ccccc1 Tamoxifen\n"+
"CNCCC(c1ccccc1)Oc2ccc(cc2)C(F)(F)F.Cl Prozac\n"+
"NC(C)Cc1ccccc1 adderall\n"+
"CNC(=C[N+](=O)[O-])NCCSCC1=CC=C(O1)CN(C)C.Cl Zantac\n"+
"Oc2cc(cc1OC(C3CCC(=CC3c12)C)(C)C)CCCCC THC\n"+
"CC(=CCO)C=CC=C(C)C=CC1=C(C)CCC1(C)C Vitamin A\n"+
"Oc1cccc(C=Cc2cc(O)cc(O)c2)c1 resveratrol\n"+
"CCOC(=O)C1=CC(OC(CC)CC)C(NC(C)=O)C(N)C1 Tamiflu\n"+
"OC(=O)CC(O)(CC(O)=O)C(O)=O.CCCc1nn(C)c2c1NC(=NC2=O)c1cc(ccc1OCC)S(=O)(=O)N1CCN(C)CC1 Viagra\n"+
"COc1cc(C=CC(=O)CC(=O)C=Cc2ccc(O)c(OC)c2)ccc1O Curcumin\n"+
"COC1CC(CCC1O)C=C(C)C1OC(=O)C2CCCCN2C(=O)C(=O)C2(O)OC(C(CC2C)OC)C(CC(C)CC(C)=CC(CC=C)C(=O)CC(O)C1C)OC Tacrolimus\n"+
"CC12CC(=O)C3C(CCC4=CC(=O)CCC34C)C1CCC2(O)C(=O)CO Cortisone\n"+
"CN1c2ccc(cc2C(=NCC1=O)c3ccccc3)Cl Valium\n");
    String js = (
"var DEMO_DATASET=`"+DEMO_DATASET+"`;\n"+
"function go_smartsfilter(form, formmode)\n"+
"{\n"+
"  if (!checkform(form,formmode)) return;\n"+
"  var runmode;\n"+
"  for (i=0;i<form.runmode.length;++i)\n"+ //radio
"    if (form.runmode[i].checked)\n"+
"      runmode=form.runmode[i].value;\n"+
"  if (runmode=='filter')\n"+
"  {\n"+
"    var x=300;\n"+
"    if (typeof window.screenX!='undefined') x+=window.screenX;\n"+
"    else x+=window.screenLeft; //IE\n"+
"    var y=300;\n"+
"    if (typeof window.screenY!='undefined') y+=window.screenY;\n"+
"    else y+=window.screenTop; //IE\n"+
"    var winargs='width=400,height=100,left='+x+',top='+y+',scrollbars=1,resizable=1,location=0,status=0,toolbar=0';\n"+
"    var pwin=window.open('','"+PROGRESS_WIN_NAME+"',winargs);\n"+
"    if (!pwin) {\n"+
"      alert('ERROR: popup windows must be enabled for progress indicator.');\n"+
"      return false;\n"+
"    }\n"+
"    pwin.focus();\n"+
"    pwin.document.close(); //if window exists, clear\n"+
"    pwin.document.open('text/html');\n"+
"    pwin.document.writeln('<HTML><HEAD>');\n"+
"    pwin.document.writeln('<LINK REL=\"stylesheet\" type=\"text/css\" HREF=\""+PROXY_PREFIX+CONTEXTPATH+"/css/biocomp.css\" />');\n"+
"    pwin.document.writeln('</HEAD><BODY BGCOLOR=\"#DDDDDD\">');\n"+
"    pwin.document.writeln('"+SERVLETNAME+"...<BR>');\n"+
"    pwin.document.writeln('"+DateFormat.getDateInstance(DateFormat.FULL).format(new Date())+"<BR>');\n"+
"\n"+
"    if (navigator.appName.indexOf('Explorer')<0)\n"+
"      pwin.document.title = '"+SERVLETNAME+" progress'; //not-ok for IE\n"+
"\n"+
"  }\n"+
"  form.filter.value='TRUE';\n"+
"  form.submit();\n"+
"}\n"+
"function go_demo(form, formmode)\n"+
"{\n"+
"  go_init(form, formmode, false);\n"+
"  form.intxt.value=DEMO_DATASET;\n"+
"  form.depict.checked=true;\n"+
"  form.glaxo.checked=true;\n"+
"  form.pains.checked=true;\n"+
"  form.oprea.checked=true;\n"+
"  form.alarmnmr.checked=true;\n"+
"  go_smartsfilter(form, formmode);\n"+
"}\n"+
"function go_changemode(form)\n"+
"{\n"+
"  form.changemode.value='TRUE';\n"+
"  form.submit();\n"+
"}\n"+
"function checkform(form, formmode)\n"+
"{\n"+
"  if (!form.intxt.value && !form.infile.value) {\n"+
"    alert('ERROR: No input molecules specified');\n"+
"    return 0;\n"+
"  }\n"+
"  if (!form.out_view.checked && !form.out_batch.checked) {\n"+
"    alert('ERROR: No output specified (view and/or batch).');\n"+
"    return 0;\n"+
"  }\n"+
"  if (!(\n"+
"        (typeof form.intxt_sma!='undefined' && form.intxt_sma.value) ||\n"+
"        (typeof form.infile_sma!='undefined' && form.infile_sma.value)\n"+
"    ) &&\n"+
"      !(typeof form.glaxo!='undefined' && form.glaxo.checked) &&\n"+
"      !(typeof form.glaxo!='undefined' && form.blakelint.checked) &&\n"+
"      !(typeof form.oprea!='undefined' && form.oprea.checked) &&\n"+
"      !(typeof form.pains!='undefined' && form.pains.checked) &&\n"+
"      !(typeof form.alarmnmr!='undefined' && form.alarmnmr.checked) &&\n"+
"      !(typeof form.mlsmr_orig!='undefined' && form.mlsmr_orig.checked) &&\n"+
"      !(typeof form.mlsmr!='undefined' && form.mlsmr.checked) &&\n"+
"      !(typeof form.ursu!='undefined' && form.ursu.checked) &&\n"+
"      !(typeof form.toxic!='undefined' && form.toxic.checked) \n"+
"      )\n"+
"  {\n"+
"    alert('ERROR: No smarts specified');\n"+
"    return 0;\n"+
"  }\n"+
"  if (formmode=='expert')\n"+
"  {\n"+
"    if (!form.inc_pass.checked && !form.inc_fail.checked) {\n"+
"      alert('ERROR: No output specified (passes and/or fails).');\n"+
"      return 0;\n"+
"    }\n"+
"  }\n"+
"  return 1;\n"+
"}\n"+
"function go_init(form, formmode, changemode)\n"+
"{\n"+
"  if (formmode=='expert')\n"+
"  {\n"+
"    form.intxt_sma.value='';\n"+
"    if (typeof form.fixmols!='undefined') form.fixmols.checked=false;\n"+
"    form.strict.checked=false;\n"+
"    form.inc_pass.checked=true;\n"+
"    form.inc_fail.checked=false;\n"+
"    form.fullann.checked=true;\n"+
"    form.showmatches.checked=false;\n"+
"    form.mlsmr.checked=false;\n"+
"    form.mlsmr_orig.checked=false;\n"+
"    form.ursu.checked=false;\n"+
"    form.toxic.checked=false;\n"+
"    for (i=0;i<form.arom.length;++i)\n"+ //radio
"      if (form.arom[i].value=='gen')\n"+
"        form.arom[i].checked=true;\n"+
"  }\n"+
"  if (changemode) return;\n"+
"  for (i=0;i<form.runmode.length;++i)\n"+ //radio
"    if (form.runmode[i].value=='filter')\n"+
"      form.runmode[i].checked=true;\n"+
"  for (i=0;i<form.ofmt.length;++i)\n"+ //radio
"    if (form.ofmt[i].value=='smiles')\n"+
"      form.ofmt[i].checked=true;\n"+
"  form.file2txt.checked=false;\n"+
"  form.depict.checked=true;\n"+
"  form.glaxo.checked=false;\n"+
"  form.blakelint.checked=false;\n"+
"  form.oprea.checked=false;\n"+
"  form.alarmnmr.checked=false;\n"+
"  form.intxt.value='';\n"+
"  var i;\n"+
"  for (i=0;i<form.molfmt.length;++i)\n"+
"    if (form.molfmt.options[i].value=='automatic')\n"+
"      form.molfmt.options[i].selected=true;\n"+
"  form.out_batch.checked=true;\n"+
"  form.out_view.checked=false;\n"+
"  form.verbose.checked=false;\n"+
"}\n"
    );
    return js;
  }
  /////////////////////////////////////////////////////////////////////////////
  private static String HelpHtm()
  {
    String htm="";
    htm+=(
"<H3>"+APPNAME+" help</H3>\n"+
"In \"filter\" mode, filter a set of molecules in one\n"+
"mode and inspect the matches (first failures).  In \"analyze\n"+
"one molecule\" mode a single molecule is tested against\n"+
"all filters and all matches are can be viewed.\n"+
"</P><P>\n"+
"SMARTS sets:\n"+
"<UL>\n"+
"<LI><B>Blake:</B>\n"+
"This set is from\n"+
"James Blake of Array Biopharma (published as contributed\n"+
"Sybyl script lint_sln.spl, formerly bundled with Sybyl).\n"+
"The Hahn set was published in smarts format, but the Blake\n"+
"set is in SLN which have been translated to SMARTS.\n"+
"<LI><B>Glaxo:</B>\n"+
"The Glaxo set<SUP>1</SUP> is comprised of a \"unsuitable\n"+
"leads\" subset, an \"unsuitable natural products\" subset,\n"+
"and a reactive subset (see attached).  Optional Glaxo\n"+
"subsets are electrophilic and nucleophilic.  Other Glaxo\n"+
"subsets are acids and bases, which, as I understand it,\n"+
"determine how compounds can or cannot be \"pooled\" in a\n"+
"single well in many-compound-one-assay multiplexed HTS,\n"+
"and do not indicate that a compound is unsuitable for HTS.\n"+
"<LI><B>ALARM NMR:</B>\n"+
"The ALARM NMR set derives from the published Abbott method<SUP>2</SUP>\n"+
"and the supplemental materials from that paper.\n"+
"<LI><B>Oprea</B>\n"+
"Developed by Tudor Oprea, for general\n"+
"multi-objective library fitness.\n"+
"<LI><B>toxicity</B>\n"+
"Developed by Oleg Ursu, informed by\n"+
"published sources<SUP>3,4</SUP>.\n"+
"<LI><B>MLSMR, MLSMR-orig</B>\n"+
"The MLSMR-orig set was developed by DPI for use with the\n"+
"NIH Roadmap.  The MLSCN Chemistry Committee relaxed these\n"+
"rules, resulting the the current filters, here named MLSMR.\n"+
"<LI><B>PAINS</B>\n"+
"\"Pan-Assay Interference Compounds\", Developed by Jonathan Baell and Georgina Holloway, of The\n"+
"Walter and Eliza Hall Institute of Medical Research,\n"+
"Bundoora, Victoria, Australia.<SUP>5</SUP>\n"+
"NOTE: The PAINS SMARTS were translated from tables S6, S7, and S8,\n"+
"downloaded from the blog of Rajarshi Guha, at\n"+
"http://blog.rguha.net/?p=850, which indicates translation via CACTVS.\n"+
"</UL>\n"+
"<P>\n"+
"Note that the logic of smarts or any substructure\n"+
"based filtering is dependent on the standardization\n"+
"of valence forms.  For example, and in particular,\n"+
"nitro groups should be in pentavalent form (-N(=O)=O)\n"+
"and not charge separated (-[N+](=O)[O-]).  Carboxylic\n"+
"acid groups should be protonated (-C(=O)[OH]) and not\n"+
"deprotonated/charged (-C(=O)[O-]) although smarts can\n"+
"be written to accomodate such variations.  For this\n"+
"application valence standardizations are applied prior to\n"+
"matching to accomodate non-standardized input.\n"+
"<P>\n"+
"<B>References:</B>\n"+
"<UL>\n"+
"<LI><SUP>1</SUP>\"Strategic Pooling of Compounds for\n"+
"High-Throughput Screening\", Mike Hahn et al., J. Chem.  Inf.\n"+
"Comput. Sci. 1999, 39, 897-902.\n"+
"<LI><SUP>2</SUP>\"ALARM NMR: A Rapid and Robust Experimental\n"+
"Method To Detect Reactive False Positives in Biochemical Screens\",\n"+
"J. R. Huth, R. Mendoza, E. T. Olejniczak, R. W. Johnson,\n"+
"D. A. Cothron, Y. Liu, C. G. Lerner, J. Chem and P. J. Hajduk,\n"+
"J. Am. Chem. Soc., 2005, 127, 217-224.\n"+
"<LI><SUP>3</SUP>\"Casarett & Doull's Toxicology\", 6th ed., C. D.\n"+
"Klaassen, ed., McGraw-Hill, 2001.\n"+
"<LI><SUP>4</SUP>\"Derivation and Validataion of Toxicophores for\n"+
"Mutagenicity Prediction\", J. Kazius, R. McGuire, R. Bursi, J.  Med.\n"+
"Chem, 2005, 48, 312-320.\n"+
"<LI><SUP>5</SUP>\"New Substructure Filters for Removal of Pan Assay\n"+
"Interference Compounds (PAINS) from Screening Libraries and for their\n"+
"Exclusion in Bioassays\", J. B. Baell and G. A. Holloway,  J. Med. Chem,\n"+
" 2010, Vol. 53, No 7, 2719-2740.\n"+
"<LI>\"Analysis and hit filtering of a very large library of compounds screened\n"+
"against Mycobacterium tuberculosis\", S. Ekins, T. Kaneko, C. A. Lipinski,\n"+
"J. Bradford, K. Dole, A. Spektor, K. Gregory, D. Blondeau, S. Ernst,\n"+
"J. J. Yang, N. Goncharoff, M. M. Hohman, and B. A. Bunin, Mol. BioSyst.,\n"+
"2010, 6, 2316-2324.<I><B>(cites this web app)</B></I>\n"+
"</UL>\n"+
"<P>\n"+
"<B>Web app features:</B>\n"+
"<UL>\n"+
"<LI>The show matches checkbox specifies that all matching\n"+
"patterns will be shown in the view output.\n"+
"<LI>The include passes|fails checkboxes means that passed|failed\n"+
"molecules will be included in the output.\n"+
"<LI>The normalize mols checkbox implements normalization to\n"+
"input molecules via the following normalizing smirks:\n"+
"<UL>");
    for (String smirks:STD_SMIRKSES)
      htm+=("<LI><CODE>"+smirks.replace(">","&gt;")+"</CODE>\n");
    htm+=(
"</UL>\n"+
"<LI>The strict smarts checkbox only applies to uploaded smarts.\n"+
"Here strict simply means parsing errors are not permitted;\n"+
"with strict off, bad smarts are ignored.\n"+
"</UL>\n"+
"This web app consists of (1) a Java servlet using JChem\n"+
"for the user interface, and (2) a Java servlet using JChem which\n"+
"generates inline PNG graphics.\n"+
"The list of molecule formats is automatically generated by JChem.\n"+
"<P>\n"+
"Canned smarts files are pre-loaded and parsed at servlet init for efficiency.\n"+
"<P>\n"+
"configured with <UL>\n"+
"<LI> N_MAX = "+N_MAX+"\n"+
"<LI> N_MAX_VIEW = "+N_MAX_VIEW+"\n"+
"</UL>\n"+
"<P>\n"+
"Molecule normalization is: "+
(stdizer_islicensed?"enabled":"disabled - no ChemAxon license for Standardizer")+"\n"+
"<P>\n"+
"Thanks to ChemAxon for the use of JChem in this application.\n"+
"<P>\n"+
"author/support: Jeremy Yang\n");
    return  htm;
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Parses all smarts files into SmartsFile objects.
	By doing this here in init(), then it happens only
	once for each servlet instance and not each submit.
  */
  public void init(ServletConfig conf) throws ServletException
  {
    super.init(conf);
    CONTEXT = getServletContext();
    CONTEXTPATH = CONTEXT.getContextPath();
    try { APPNAME = conf.getInitParameter("APPNAME"); }
    catch (Exception e) { APPNAME = this.getServletName(); }
    UPLOADDIR = conf.getInitParameter("UPLOADDIR");
    if (UPLOADDIR==null)
      throw new ServletException("Please supply UPLOADDIR parameter");
    SCRATCHDIR = conf.getInitParameter("SCRATCHDIR");
    if (SCRATCHDIR==null) { SCRATCHDIR="/tmp"; }
    try { N_MAX = Integer.parseInt(conf.getInitParameter("N_MAX")); }
    catch (Exception e) { N_MAX=1000; }
    try { N_MAX_VIEW = Integer.parseInt(conf.getInitParameter("N_MAX_VIEW")); }
    catch (Exception e) { N_MAX_VIEW=100; }
    try { ENABLE_NOLIMIT = Boolean.parseBoolean(conf.getInitParameter("ENABLE_NOLIMIT")); }
    catch (Exception e) { ENABLE_NOLIMIT=false; }
    try { MAX_POST_SIZE = Integer.parseInt(conf.getInitParameter("MAX_POST_SIZE")); }
    catch (Exception e) { MAX_POST_SIZE=10*1024*1024; }
    PROXY_PREFIX = ((conf.getInitParameter("PROXY_PREFIX")!=null)?conf.getInitParameter("PROXY_PREFIX"):"");
    SMARTSDIR = CONTEXT.getRealPath("")+"/data/smarts"; //Works.
    ArrayList<String> smartsfnames = new ArrayList<String>();
    smartsfnames.addAll(Arrays.asList(glaxo_files));
    smartsfnames.addAll(Arrays.asList(ursu_files));
    smartsfnames.addAll(Arrays.asList(alarmnmr_files));
    smartsfnames.addAll(Arrays.asList(blakelint_files));
    smartsfnames.addAll(Arrays.asList(oprea_files));
    smartsfnames.addAll(Arrays.asList(mlsmr_orig_files));
    smartsfnames.addAll(Arrays.asList(mlsmr_files));
    smartsfnames.addAll(Arrays.asList(toxic_files));
    smartsfnames.addAll(Arrays.asList(pains_files));
    SMARTSFILES = new LinkedHashMap<String,SmartsFile>();
    for (String smafile: smartsfnames)
    {
      SmartsFile sf = new SmartsFile();
      try {
        sf.parseFile(new File(SMARTSDIR+"/"+smafile), false, smafile.replaceFirst("\\..*$", ""));
      }
      catch (Exception e) {
        throw new ServletException("problem reading smarts file: "+e.getMessage());
      }
      SMARTSFILES.put(smafile, sf);
      CONTEXT.log("loaded smarts file: "+smafile+" ("+sf.getRawtxt().length()+" bytes, "
        +sf.size()+" smarts, "
        +sf.getDefines().size()+" defines, "
        +sf.getFailedsmarts().size()+" failed smarts)");
    }
  }
  /////////////////////////////////////////////////////////////////////////////
  public void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException,ServletException
  {
    doPost(request, response);
  }
}
