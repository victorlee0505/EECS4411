connect to STL;

select P.cust#, count(P.Book#)
from stl.Purchase P
group by P.cust#;

connect reset;
terminate;


