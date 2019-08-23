package edu.unm.health.biocomp.smarts;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import chemaxon.formats.*;
import chemaxon.util.*;
import chemaxon.struc.*;
import chemaxon.struc.prop.MMoleculeProp;
import chemaxon.sss.search.*;
import chemaxon.reaction.*; //Standardizer, StandardizerException
 
import edu.unm.health.biocomp.util.threads.*;
 
/**	Callable task for smarts searching.

	@author Jeremy J Yang
*/
public class Smartsfilter_Task
	implements Callable<Boolean>
{
  private MolImporter molReader;
  private Integer arom;
  private Standardizer stdizer;
  private Float sim_min;
  private Integer n_max;
  private SmartsFile smaf;
  public TaskStatus taskstatus;
  private int n_total;
  private int n_done;
  private int n_err;
  private ArrayList<String> errors;
  private Date t0;
  private Vector<SmartsfilterResult> results;
  public Smartsfilter_Task(MolImporter _molReader,
	SmartsFile _smaf,
	Integer _arom,Standardizer _stdizer,Integer _n_max)
  {
    this.molReader=_molReader;
    this.smaf=_smaf;
    this.arom=_arom;
    this.stdizer=_stdizer;
    this.n_max=_n_max;
    this.taskstatus=new Status(this);
    this.n_total=0;
    this.n_done=0;
    this.n_err=0;
    this.errors = new ArrayList<String>();
    this.t0 = new Date();
    results = new Vector<SmartsfilterResult>();
  }
  /////////////////////////////////////////////////////////////////////////
  public List<String> getErrors() { return this.errors; }
  public int getErrorCount() { return this.n_err; }
  /////////////////////////////////////////////////////////////////////////
  public synchronized Vector<SmartsfilterResult> getResults() { return results; }
  public synchronized Boolean call()
  {
    for (int i=0;true;++i,++n_done)
    {
      if (n_max>0 && i==n_max) break;
      Molecule mol;
      if (molReader==null) { return false; } //ERROR
      
      try { mol=molReader.read(); }
      catch (Exception e) {
        ++n_err;
        this.errors.add("["+(i+1)+"] "+e.getMessage());
        continue;
      }
      if (mol==null) break; //EOF

      SmartsfilterResult result = new SmartsfilterResult();
      result.setIndex(i);
      results.add(result);

      if (arom!=null)
        mol.aromatize(arom);
      else
        mol.dearomatize();
      if (stdizer!=null)
      {
        try { stdizer.standardize(mol); }
        catch (Exception e) { }
      }
      try { results.get(i).setSmiles(MolExporter.exportToFormat(mol,"smiles:r1")); } // r1 = low rigor; avoid expressibility errors
      catch (Exception e) { //Should not happen.
        results.get(i).setSmiles("");
        ++n_err;
        this.errors.add("["+(i+1)+"] "+e.getMessage());
      }
      results.get(i).setName(mol.getName());

      for (int j=0;j<smaf.size();++j)
      {
        Smarts smrt = smaf.getSmarts(j);
        boolean hit=false;
        try {
          smrt.getSearch().setTarget(mol);
          hit=smrt.getSearch().isMatching();
        }
        catch (Exception e) {
          errors.add("["+(i+1)+"] ERROR: "+mol.getName()+" "+smrt.getSmarts()+" "+smrt.getName()+" "+e.getMessage());
          ++n_err;
        }
        if (hit)
        {
          results.get(i).getMatches().add(new Smarts(smrt.getRawsmarts(),smrt.getSmarts(),smrt.getName(),smrt.getGroupname(),smrt.getSearch()));
        }
      }
      if (molReader!=null)
      {
        n_total=molReader.estimateNumRecords();
        if (n_max>0) n_total=Math.min(n_total,n_max);
      }
    }
    return true;
  }
  class Status implements TaskStatus
  {
    private Smartsfilter_Task task;
    public Status(Smartsfilter_Task task) { this.task=task; }
    public String status()
    {
      String statstr=(String.format("%5d;",task.n_done));
      if (task.n_total>0)
        statstr+=(String.format(" %.0f%%",100.0f*task.n_done/task.n_total));
      return statstr;
    }
  }
}
