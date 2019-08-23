package edu.unm.health.biocomp.smarts;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.text.*;

import chemaxon.util.MolHandler;
import chemaxon.sss.search.MolSearch;
import chemaxon.struc.Molecule;
import chemaxon.formats.MolFormatException;
import chemaxon.sss.search.SearchException;

/**	Handles smarts file as set of patterns.
	Parses smarts, expanding defines (def before ref).
	Requires ChemAxon license.  Probably should check.
	@author Jeremy J Yang
*/
public class SmartsFile
{
  private ArrayList<Smarts> smartses;
  private HashMap<String,String> defines;
  private ArrayList<String> failed_smarts;
  private ArrayList<String> groupnames;
  private String rawtxt;
  private String name;

  public HashMap<String,String> getDefines() { return this.defines; }
  public ArrayList<String> getFailedsmarts() { return this.failed_smarts; }
  public ArrayList<String> getGroupnames() { return this.groupnames; }
  public String getRawtxt() { return this.rawtxt; }
  public String getName() { return this.name; }

  public SmartsFile()	//default constructor
  {
    this.smartses=new ArrayList<Smarts>();
    this.defines=new HashMap<String,String>();
    this.failed_smarts=new ArrayList<String>();
    this.groupnames=new ArrayList<String>();
    this.rawtxt="";
  }
  public SmartsFile(SmartsFile sf)	//copy constructor
  {
    this.smartses=new ArrayList<Smarts>(sf.smartses);
    this.defines=new HashMap<String,String>(sf.defines);
    this.failed_smarts=new ArrayList<String>(sf.failed_smarts);
    this.groupnames=new ArrayList<String>(sf.groupnames);
    this.rawtxt=new String(sf.rawtxt);
  }
  /////////////////////////////////////////////////////////////////////////////
  public int size() { return this.smartses.size(); }
  public MolSearch getSearch(int i) { return this.smartses.get(i).getSearch(); }
  public Smarts getSmarts(int i)
  {
    return this.smartses.get(i); 
  }
  /////////////////////////////////////////////////////////////////////////////
  public boolean parseFile(String ftxt,boolean strict,String groupname)
    throws IOException,Exception
  {
    BufferedReader buff=new BufferedReader(new StringReader(ftxt));
    String line;
    Pattern p_comment=Pattern.compile("^\\s*#.*$");
    Pattern p_blank=Pattern.compile("^\\s*$");
    Pattern p_define=Pattern.compile("^define\\s+\\$(\\S+)\\s+(\\S+)\\s*$",
	Pattern.CASE_INSENSITIVE);
    Pattern p_smarts_name=Pattern.compile("^(\\S+)\\s+(.+)$");
    while ((line=buff.readLine())!=null)
    {
      this.rawtxt+=(line+"\n");
      Matcher m_comment=p_comment.matcher(line);
      Matcher m_blank=p_blank.matcher(line);
      Matcher m_define=p_define.matcher(line);
      Matcher m_smarts_name=p_smarts_name.matcher(line);
      String rawsmarts="";
      String smarts="";
      String name="";
      if (m_comment.find() || m_blank.find())
      {
        continue;  //comment/blank lines
      }
      else if (m_define.find())
      {
        String tag=m_define.group(1);
        String val=m_define.group(2);
        this.defines.put(tag,val);
        continue;
      }
      else if (m_smarts_name.find())
      {
        rawsmarts=m_smarts_name.group(1);
        name=m_smarts_name.group(2).trim();
      }
      else
      {
        rawsmarts=line.trim();
        name="";
      }
      smarts=resolveSmarts(rawsmarts,strict);

      MolSearch search=new MolSearch();
      MolHandler smartsReader=new MolHandler();
      smartsReader.setQueryMode(true);

      // kludge: fix "x2", not supported in JChem 5.0,
      // by translating to "R1".
      // smarts=smarts.replace("x2","R1");

      try {
        smartsReader.setMolecule(smarts);
        search.setQuery(smartsReader.getMolecule());
      }
      catch (Exception e) {
        if (strict)
          throw new Exception("problem parsing smarts: "+smarts+" "+name+"\n"+e.getMessage());
        else
        {
          //this.failed_smarts.add(smarts+" "+name);
          this.failed_smarts.add(smarts);
          continue;
        }
      }
      if (!this.groupnames.contains(groupname)) this.groupnames.add(groupname);
      this.smartses.add(new Smarts(rawsmarts,smarts,name,groupname,search));
    }
    return true;
  }

  /////////////////////////////////////////////////////////////////////////////
  public boolean parseFile(File smartsfile,boolean strict,String groupname)
    throws IOException,FileNotFoundException,Exception
  {
    String filetxt="";
    String line="";
    BufferedReader buff=new BufferedReader(new FileReader(smartsfile));
    while ((line=buff.readLine())!=null)
    {
      filetxt+=(line+"\n");
    }
    boolean ok=parseFile(filetxt,strict,groupname);
    for (int i=0;i<this.smartses.size();++i)
    {
      if (this.smartses.get(i).getName().isEmpty()) 
        this.smartses.get(i).setName(""+(i+1));
    }
    this.name=smartsfile.getName();
    return ok;
  }

  /////////////////////////////////////////////////////////////////////////////
  public boolean mergeFiles(SmartsFile sf2)
    throws Exception
  {
    this.smartses.addAll(sf2.smartses);
    this.failed_smarts.addAll(sf2.failed_smarts);
    for (String groupname : sf2.groupnames)
    {
      if (!this.groupnames.contains(groupname)) this.groupnames.add(groupname);
    }
    this.rawtxt+=(sf2.rawtxt);
    for (String key:sf2.defines.keySet())
    {
      if (this.defines.get(key)==null)
        this.defines.put(key,sf2.defines.get(key));
      else
        throw new Exception("reused define tag: "+key);
    }
    return true;
  }

  /////////////////////////////////////////////////////////////////////////////
  private String resolveSmarts(String smarts,boolean strict)
      throws Exception
  {
    Pattern p_tag=Pattern.compile("\\$[a-zA-Z0-9_]+");
    Matcher m_tag=p_tag.matcher(smarts);
    String rsmarts="";
    String tag="";
    int i=0; int j=0; int k=0;
    while (m_tag.find())
    {
      j=m_tag.start();
      k=m_tag.end();
      rsmarts+=smarts.substring(i,j);
      tag=smarts.substring(j+1,k);
      String smartsdef=null;
      if (this.defines.get(tag)!=null)
      {
        smartsdef=this.defines.get(tag);
        if (p_tag.matcher(smartsdef).find()) //nested definition
        {
          smartsdef=resolveSmarts(smartsdef,strict);
        }
        rsmarts+=("$("+smartsdef+")");
      }
      else
      {
        if (strict) throw new Exception("missing define: "+tag);
      }
      i=k;
    }
    if (i==0) return smarts;
    else rsmarts+=smarts.substring(i);
    return rsmarts;
  }
}
