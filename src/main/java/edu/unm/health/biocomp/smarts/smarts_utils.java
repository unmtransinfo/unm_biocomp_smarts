package edu.unm.health.biocomp.smarts;

/**	Static methods for smarts processing.
	@author Jeremy J Yang
*/
public class smarts_utils
{
  /////////////////////////////////////////////////////////////////////////////
  /**   Checks whether a string is a smarts, assuming it is either a smiles or smarts.
  */
  public static boolean IsSmarts(String str)
  {
    boolean is_smarts=false;
    is_smarts |= str.matches(".*[&;,~].*$");
    is_smarts |= str.matches(".*#[1-9].*$");
    return is_smarts;
  }
}
