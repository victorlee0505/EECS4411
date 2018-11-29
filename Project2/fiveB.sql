connect to STL;

select P.cust#, count(P.Book#)
from stl.Purchase P
where P.cust# between '1' and '10'
group by P.cust#;

connect reset;
terminate;

