package iterator;

import heap.*;
import global.*;
import diskmgr.*;
import bufmgr.*;
import index.*;
import java.io.*;
import java.util.List;

import javax.management.openmbean.ArrayType;

/*==========================================================================*/
/**
 * Sort-merge join.
 * Call the two relations being joined R (outer) and S (inner).  This is an
 * implementation of the naive sort-merge join algorithm.  The external
 * sorting utility is used to generate runs.  Then the iterator interface
 * is called to fetch successive tuples for the final merge with joining.
 */

public class SortMerge extends Iterator implements GlobalConst {

	private AttrType[] in1;
	private AttrType[] in2;
	private AttrType sortType;
	private Iterator am1;
	private Iterator am2;
	
	private int join_col_in1;
	private int join_col_in2;
	
	private Tuple tuple1;
	private Tuple tuple2;
	private Tuple tempTuple1;
	private Tuple tempTuple2;
	private Tuple Jtuple;
	private AttrType[] Jtypes;
	
    private TupleOrder  order;
    
    private IoBuf io_buf1;
    private IoBuf io_buf2;
    
    private byte bufs1[][];
    private byte bufs2[][];
    
    private int n_pages;
    
    private Heapfile temp_fd1;
    private Heapfile temp_fd2;
    
    private CondExpr  outFilter[];
    
    int nOutFlds;
    
    private  FldSpec fldSpecs[]; // field specification
    
    private boolean firstrun;
    private boolean exhausted;
    
    private boolean process_next_block;
    
    private boolean goodTuple;

    

	
	
    /**
     *constructor,initialization
     *@param in1[]         Array containing field types of R
     *@param len_in1       # of columns in R
     *@param s1_sizes      Shows the length of the string fields in R.
     *@param in2[]         Array containing field types of S
     *@param len_in2       # of columns in S
     *@param s2_sizes      Shows the length of the string fields in S
     *@param sortFld1Len   The length of sorted field in R
     *@param sortFld2Len   The length of sorted field in S
     *@param join_col_in1  The col of R to be joined with S
     *@param join_col_in2  The col of S to be joined with R
     *@param amt_of_mem    Amount of memory available, in pages
     *@param am1           Access method for left input to join
     *@param am2           Access method for right input to join
     *@param in1_sorted    Is am1 sorted?
     *@param in2_sorted    Is am2 sorted?
     *@param order         The order of the tuple: assending or desecnding?
     *@param outFilter[]   Ptr to the output filter
     *@param proj_list     Shows what input fields go where in the output tuple
     *@param n_out_flds    Number of outer relation fileds
     *@exception JoinNewFailed               Allocate failed
     *@exception JoinLowMemory               Memory not enough
     *@exception SortException               Exception from sorting
     *@exception TupleUtilsException         Exception from using tuple utils
     *@exception JoinsException              Exception reading stream
     *@exception IndexException              Exception...
     *@exception InvalidTupleSizeException   Exception...
     *@exception InvalidTypeException        Exception...
     *@exception PageNotReadException        Exception...
     *@exception PredEvalException           Exception...
     *@exception LowMemException             Exception...
     *@exception UnknowAttrType              Exception...
     *@exception UnknownKeyTypeException     Exception...
     *@exception IOException                 Some I/O fault
     *@exception Exception                   Generic
     */

    public SortMerge(
        AttrType    in1[], 
        int         len_in1,                        
        short       s1_sizes[],
        AttrType    in2[],                
        int         len_in2,                        
        short       s2_sizes[],

        int         join_col_in1,                
        int         sortFld1Len,
        int         join_col_in2,                
        int         sortFld2Len,

        int         amt_of_mem,               
        Iterator    am1,                
        Iterator    am2,                

        boolean     in1_sorted,                
        boolean     in2_sorted,                
        TupleOrder  order,

        CondExpr    outFilter[],                
        FldSpec     proj_list[],
        int         n_out_flds
    )
    throws JoinNewFailed ,
        JoinLowMemory,
        SortException,
        TupleUtilsException,
        JoinsException,
        IndexException,
        InvalidTupleSizeException,
        InvalidTypeException,
        PageNotReadException,
        PredEvalException,
        LowMemException,
        UnknowAttrType,
        UnknownKeyTypeException,
        IOException,
        Exception
    {
    	
    	this.in1 = new AttrType[in1.length];
        this.in2 = new AttrType[in2.length];
        System.arraycopy(in1,0,this.in1,0,in1.length);
        System.arraycopy(in2,0,this.in2,0,in2.length);
    
    	//this.in1 = in1;
    	//this.in2 = in2;
    	
    	this.sortType = in1[join_col_in1 - 1];
    	
    	
    	this.join_col_in1 = join_col_in1;
    	this.join_col_in2 = join_col_in2;
    	
    	if(in1_sorted) {
    		this.am1 = am1;
    	} else {
    		this.am1 = new Sort(in1, (short) len_in1, s1_sizes, am1, join_col_in1, order, sortFld1Len, amt_of_mem);
    	}
    	if(in2_sorted) {
    		this.am2 = am2;
    	} else {
    		this.am2 = new Sort(in2, (short) len_in2, s2_sizes, am2, join_col_in2, order, sortFld2Len, amt_of_mem);
    	}
    	
    	tuple1 = new Tuple();
        tuple2 =  new Tuple(); 
        tempTuple1 = new Tuple(); // tempTuple prepare for grouping
    	tempTuple2 = new Tuple();
        try {
        	tuple1.setHdr((short)len_in1, in1, s1_sizes);
        	tuple2.setHdr((short)len_in2, in2, s2_sizes);
        	tempTuple1.setHdr((short)len_in1, in1, s1_sizes);
    		tempTuple2.setHdr((short)len_in2, in2, s2_sizes);
        	}
        catch (Exception e){
        	throw new SortException (e,"Set header failed");
        	}
        

        this.order = order;
        
        Jtuple = new Tuple();
        Jtypes = new AttrType[n_out_flds];
        
        
        // initialize the buffer for group
        io_buf1 = new IoBuf();
        io_buf2 = new IoBuf();
        
        n_pages = 1;
        
        bufs1 = new byte [n_pages][MINIBASE_PAGESIZE];
        bufs2 = new byte [n_pages][MINIBASE_PAGESIZE];
        temp_fd1 = new Heapfile("fd1");
        temp_fd2 = new Heapfile("fd2");
       
        
        this.outFilter = outFilter;
        
        this.nOutFlds = n_out_flds;
        
        this.fldSpecs = proj_list;
        
        
        TupleUtils.setup_op_tuple(Jtuple, Jtypes,
			    in1, len_in1, in2, len_in2,
			    s1_sizes, s2_sizes, 
			    proj_list,n_out_flds );
    	
        // initialize condition for get_next()
        firstrun = true;
        exhausted = false;
        goodTuple = false;
    	
    	
    } // End of SortMerge constructor

/*--------------------------------------------------------------------------*/
    /**
     *Reads a tuple from a stream in a less painful way.
     */
    private boolean readTuple(
        Tuple    tuple,
        Iterator tupleStream
    )
        throws JoinsException,
            IndexException,
            UnknowAttrType,
            TupleUtilsException,
            InvalidTupleSizeException,
            InvalidTypeException,
            PageNotReadException,
            PredEvalException,
            SortException,
            LowMemException,
            UnknownKeyTypeException,
            IOException,
            Exception
    {
        Tuple temp;
        temp = tupleStream.get_next();
        if (temp != null) {
            tuple.tupleCopy(temp);
            return true;
        } else {
            return false;
        }
    } // End of readTuple

/*--------------------------------------------------------------------------*/
    /**
     *Return the next joined tuple.
     *@return the joined tuple is returned
     *@exception IOException I/O errors
     *@exception JoinsException some join exception
     *@exception IndexException exception from super class
     *@exception InvalidTupleSizeException invalid tuple size
     *@exception InvalidTypeException tuple type not valid
     *@exception PageNotReadException exception from lower layer
     *@exception TupleUtilsException exception from using tuple utilities
     *@exception PredEvalException exception from PredEval class
     *@exception SortException sort exception
     *@exception LowMemException memory error
     *@exception UnknowAttrType attribute type unknown
     *@exception UnknownKeyTypeException key type unknown
     *@exception Exception other exceptions
     */

    public Tuple get_next() 
        throws IOException,
           JoinsException ,
           IndexException,
           InvalidTupleSizeException,
           InvalidTypeException, 
           PageNotReadException,
           TupleUtilsException, 
           PredEvalException,
           SortException,
           LowMemException,
           UnknowAttrType,
           UnknownKeyTypeException,
           Exception
    {
    	int compareTuple;
    	
    	//int comp_res;
    	    	    	
    	// while one of am1 or am2 is exhausted, exit loop and return null
    	while(true) {
    		
    		/** This part of condition is not working as intended
    		 *  If the condition to terminate the loop needed to be fixed
    		 *  But I have no clue HOW.
    		 *  
    		 *  Observation: In the answer code, 
    		 *    IF 
    		 *  	1. process_next_block is true 
    		 *  	   (a. FirstRun, 
    		 *  		b. when compareTuple != 0 	(line 310)
    		 *  		c. io_buf == null 			(line 372)
    		 *  
    		 *  	IF  (get_from_in1) : Flag from while (put tuple 1 to IO_buf ) == null  (line 334)
    		 *  		2. tuple1.get_next() == null
    		 *  			return NULL
    		 *  
    		 *  	IF  (get_from_in2) : Flag from while (put tuple2 to IO_buf ) == null	(line 352)
    		 *  		3. tuple2.get_next() ==null
    		 *  			return NULL
    		 *  	
    		 *  		*these 2 IF for tuple1 & 2 sound like just return NULL if one of them is NULL
    		 * 
    		 *  
    		 *  From my understanding, 
    		 *  I need to stop the loop as soon as one of the tuple.get_next() == NULL
    		 *  I put boolean exhausted to flag when get_next() == NULL and return null in next iteration
    		 *  
    		 *  So what i did is I flag exhausted = true whenever get_next() == null (either tuple)
    		 *  Clearly i am missing something that i did not catch.
    		 *  one difference is that his IF statement extended until io_buf
    		 *  my IF statement does not, and simply return NULL
    		 */
    		
    		if (goodTuple == false) {
    			
    			goodTuple = true;
    			
    		
    		// firstrun to get tuple1 & 2
    		if(firstrun) {
    			
    			//System.out.println("firstrun");
    			if(!readTuple(tuple1, this.am1))
    				return null;
    			if(!readTuple(tuple2, this.am2))
    				return null;
        		firstrun = false;
    		}
    		    		

    		if(exhausted == true) {
    			//System.out.println("exhausted");
    			firstrun = true;
    			exhausted = false;
    			return null;
    		}
    		
    		//System.out.println("While True loop");
    		
    		
    		compareTuple = TupleUtils.CompareTupleWithTuple(sortType, tuple1, join_col_in1, tuple2, join_col_in2);
    		
    		while ((compareTuple < 0 && order.tupleOrder == TupleOrder.Ascending) ||
    				(compareTuple > 0 && order.tupleOrder == TupleOrder.Descending)) {
    			//System.out.println("loop a1");
    			if(!readTuple(tuple1, am1)) {
    				//exhausted = true;
    				return null;

    			}
    					
    			compareTuple = TupleUtils.CompareTupleWithTuple(sortType, tuple1,
        				join_col_in1, tuple2, join_col_in2);
    		}
    		
    		
    		while ((compareTuple > 0 && order.tupleOrder == TupleOrder.Ascending) ||
    				(compareTuple < 0 && order.tupleOrder == TupleOrder.Descending)) {
    			//System.out.println("loop a2");
    			if(!readTuple(tuple2, am2)) {
    				//exhausted = true;
    				return null;

    			}
    			
    			compareTuple = TupleUtils.CompareTupleWithTuple(sortType, tuple1,
        				join_col_in1, tuple2, join_col_in2);
    		}
    		
    		//System.out.println(compareTuple);
    		
    		//if matches not found
    		if(compareTuple != 0) {
    			goodTuple = false;
    			continue;
    			// skip to next iteration
    		}
    			
    			//Join them and return Jtuple
    			
    			// Join tuple process:
	    		// we allocate a temporary tuple-buffer for the group
	        	io_buf1.init(bufs1, n_pages, tuple1.size(), temp_fd1);
	    	    io_buf2.init(bufs2, n_pages, tuple2.size(), temp_fd2);
	    	    
	    	    // tempTuple save current tuple and using next() iterate heap file
	    	    tempTuple1.tupleCopy(tuple1);
	  	      	tempTuple2.tupleCopy(tuple2);
	  	      	
	    		// group same value and save to io_buf
	  	      	while (TupleUtils.CompareTupleWithTuple(sortType, tuple1, join_col_in1, tempTuple1, join_col_in1) == 0) {
	  	      		//System.out.println("io_buf1 loop b1");
	  	      		// insert tuple1 into io_buf1
	  	      		try {
	  	      			io_buf1.Put(tuple1);
	  	      		}
	  	      		catch (Exception e) {
	  	      			throw new JoinsException(e,"IoBuf error in sortmerge");
	  	      		}
	  	      		if (!readTuple(tuple1, am1)) {
	  	      		//increment tuple1 until 1. compare !=0 2.exhausted
	  	      			exhausted = true;
	  	      			break;
	  	      		}
	  	      	}
	  	      	
	  	      while (TupleUtils.CompareTupleWithTuple(sortType, tuple2, join_col_in2, tempTuple2, join_col_in2) == 0) {
		      		// insert tuple2 into io_buf2
	  	    	//System.out.println("io_buf2 loop b2");
		      		try {
		      			io_buf2.Put(tuple2);
		      		}
		      		catch (Exception e){
		      			throw new JoinsException(e,"IoBuf error in sortmerge");
		      		}
		      		if (!readTuple(tuple2, am2)) {
		      			//increment tuple2 until 1. compare !=0 2.exhausted
		      			exhausted = true;	
		      			break;
		      		}	
	  	      }
		      	
 
	  	      // check whether or not io_buf1 is empty
	  	      if (io_buf1.Get(tempTuple1) == null) {
	  	    	  System.out.println( "Equiv. class 1 in sort-merge has no tuples");
	  	    	  continue;
	  	      }
    		}

	  	      
/***********************/
	  	      
	  	      // if io_buf2 is empty means there is no same value with tuple2 in relation2
	  	      if (io_buf2.Get(tempTuple2) == null) {
	  	    	  // 
	  	    	  if (io_buf1.Get(tempTuple1) == null) {
	  	    		  //System.out.println("io if null");
	  	    		goodTuple = false;
	  	    		continue;
	  	    	  } else {
	  	    		  io_buf2.reread();
	  	    		  io_buf2.Get(tempTuple2);
	  	    	  }
	  	      }
	  	      
	  	      //System.out.println("PreEval: "+ PredEval.Eval(outFilter, tempTuple1, tempTuple2, in1, in2));
	  	      // check whether or not current tuple1 and tuple2 can join
	  	      // if it can, result stores in jTuple and return
	  	      if (PredEval.Eval(outFilter, tempTuple1, tempTuple2, in1, in2) == true) {
	  	    	  Projection.Join(tempTuple1, in1, tempTuple2, in2, Jtuple, fldSpecs, nOutFlds);
	  	    	  //System.out.println("return Jtuple");

	  	    	  goodTuple = true;
	  	    	  return Jtuple;
	  	      }else {
	  	    	  //goodTuple = false;
	  	      }

    	}
    	

    } // End of get_next

/*--------------------------------------------------------------------------*/
    /** 
     *implement the abstract method close() from super class Iterator
     *to finish cleaning up
     *@exception IOException I/O error from lower layers
     *@exception JoinsException join error from lower layers
     *@exception IndexException index access error 
     */

    public void close() 
        throws JoinsException, 
            IOException
    {
    	
    	 if (!closeFlag) {
    			
    			try {
    			  this.am1.close();
    			  this.am2.close();
    			}catch (Exception e) {
    			  throw new JoinsException(e, "SortMerge.java: error in closing iterator.");
    			}
    			
    			if (temp_fd1 != null) {
    			  try {
    				  temp_fd1.deleteFile();
    			  }
    			  catch (Exception e) {
    			    throw new JoinsException(e, "SortMerge.java: delete file failed");
    			  }
    			  temp_fd1 = null; 
    			}
    			
    			
    			if (temp_fd2 != null) {
    			  try {
    				  temp_fd2.deleteFile();
    			  }
    			  catch (Exception e) {
    			    throw new JoinsException(e, "SortMerge.java: delete file failed");
    			  }
    			  temp_fd2 = null; 
    			}
    			closeFlag = true;
    	 }
    		    
    } // End of close

} // End of CLASS SortMerge