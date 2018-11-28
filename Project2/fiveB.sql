connect to STL;

select P.cust#, count(P.book#) as TOTAL_BOOKS_OWN
from stl.Purchase P, stl.Customer C
where P.cust# = C.cust# and C.cust# ='1'
group by P.cust#;


connect reset;
terminate;
