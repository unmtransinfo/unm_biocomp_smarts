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

/**	Container for a smarts based query.
	@author Jeremy J Yang
*/
public class Smarts
{
  private String smarts;
  private String rawsmarts;
  private String name;
  private String groupname;
  private MolSearch search;

  public Smarts()	//default constructor
  {
    this.smarts="";
    this.rawsmarts="";
    this.name="";
    this.groupname="";
    this.search=null;
  }
  public Smarts(String _smarts,String _rawsmarts,String _name,String _groupname,MolSearch _search)
  {
    this.smarts=_smarts;
    this.rawsmarts=_rawsmarts;
    this.name=_name;
    this.groupname=_groupname;
    this.search=_search;
  }

  public String getSmarts() { return this.smarts; }
  public void setSmarts(String _smarts) { this.smarts=_smarts; }
  /**	which may include nested definitions from SmartsFile. */
  public String getRawsmarts() { return this.rawsmarts; }
  public void setRawsmarts(String _rawsmarts) { this.rawsmarts=_rawsmarts; }
  public String getName() { return this.name; }
  public void setName(String _name) { this.name=_name; }
  public String getGroupname() { return this.groupname; }
  public void setGroupname(String _groupname) { this.groupname=_groupname; }
  public MolSearch getSearch() { return this.search; }
  public void setSearch(MolSearch _search) { this.search=_search; }
  public String toString() { return this.smarts+" "+this.name; }
}
