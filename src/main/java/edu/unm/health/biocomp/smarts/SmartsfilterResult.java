package edu.unm.health.biocomp.smarts;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import chemaxon.formats.*;
import chemaxon.util.MolHandler;
import chemaxon.struc.Molecule;
import chemaxon.struc.MoleculeGraph;
import chemaxon.struc.prop.MMoleculeProp;
import chemaxon.sss.search.MolSearch;
import chemaxon.sss.search.SearchException;
import chemaxon.reaction.Standardizer;
import chemaxon.reaction.StandardizerException;
 
/**	Represents all the matches for a set of smarts against a 
	single molecule.
	@author Jeremy J Yang
*/
public class SmartsfilterResult
{
  private int i;
  private String smiles;
  private String name; //molname
  private ArrayList<Smarts> matches;

  public Integer getIndex() { return this.i; }
  public void setIndex(Integer _i) { this.i=_i; }
  public String getSmiles() { return this.smiles; }
  public void setSmiles(String _smiles) { this.smiles=_smiles; }
  public String getName() { return this.name; }
  public void setName(String _name) { this.name=_name; }
  public ArrayList<Smarts> getMatches() { return this.matches; }

  public SmartsfilterResult()
  {
    this.matches = new ArrayList<Smarts>();
  }
  public Boolean pass() { return (this.matches.size()==0); }
  public Boolean groupHasMatch(String groupname)
  {
    for (Smarts match : this.matches)
    {
      if (match.getGroupname().equals(groupname)) return true;
    }
    return false; 
  }
}

