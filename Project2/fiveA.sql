connect to STL;

select P.cust#, sum(P.qnty)
from stl.Purchase P, stl.Customer C
where P.cust# = C.cust# and C.cust# = 1
group by P.cust#;

connect reset;
terminate;

