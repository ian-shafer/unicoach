* RFC 147 fixture: a SUBSET of the published HD2023.do, verbatim per line.
* The obereg and stabbr sets are complete (us_states is built from their
* intersection and asserts the 50 states); the rest are representative rows,
* including the caret apostrophe encoding and the -2 not-in-universe sentinel.

label define label_obereg 0 "U.S. Service schools"
label define label_obereg 1 "New England (CT, ME, MA, NH, RI, VT)", add
label define label_obereg 2 "Mid East (DE, DC, MD, NJ, NY, PA)", add
label define label_obereg 3 "Great Lakes (IL, IN, MI, OH, WI)", add
label define label_obereg 4 "Plains (IA, KS, MN, MO, NE, ND, SD)", add
label define label_obereg 5 "Southeast (AL, AR, FL, GA, KY, LA, MS, NC, SC, TN, VA, WV)", add
label define label_obereg 6 "Southwest (AZ, NM, OK, TX)", add
label define label_obereg 7 "Rocky Mountains (CO, ID, MT, UT, WY)", add
label define label_obereg 8 "Far West (AK, CA, HI, NV, OR, WA)", add
label define label_obereg 9 "Other U.S. jurisdictions (AS, FM, GU, MH, MP, PR, PW, VI)", add
label define label_stabbr AL "Alabama"
label define label_stabbr AK "Alaska", add
label define label_stabbr AZ "Arizona", add
label define label_stabbr AR "Arkansas", add
label define label_stabbr CA "California", add
label define label_stabbr CO "Colorado", add
label define label_stabbr CT "Connecticut", add
label define label_stabbr DE "Delaware", add
label define label_stabbr DC "District of Columbia", add
label define label_stabbr FL "Florida", add
label define label_stabbr GA "Georgia", add
label define label_stabbr HI "Hawaii", add
label define label_stabbr ID "Idaho", add
label define label_stabbr IL "Illinois", add
label define label_stabbr IN "Indiana", add
label define label_stabbr IA "Iowa", add
label define label_stabbr KS "Kansas", add
label define label_stabbr KY "Kentucky", add
label define label_stabbr LA "Louisiana", add
label define label_stabbr ME "Maine", add
label define label_stabbr MD "Maryland", add
label define label_stabbr MA "Massachusetts", add
label define label_stabbr MI "Michigan", add
label define label_stabbr MN "Minnesota", add
label define label_stabbr MS "Mississippi", add
label define label_stabbr MO "Missouri", add
label define label_stabbr MT "Montana", add
label define label_stabbr NE "Nebraska", add
label define label_stabbr NV "Nevada", add
label define label_stabbr NH "New Hampshire", add
label define label_stabbr NJ "New Jersey", add
label define label_stabbr NM "New Mexico", add
label define label_stabbr NY "New York", add
label define label_stabbr NC "North Carolina", add
label define label_stabbr ND "North Dakota", add
label define label_stabbr OH "Ohio", add
label define label_stabbr OK "Oklahoma", add
label define label_stabbr OR "Oregon", add
label define label_stabbr PA "Pennsylvania", add
label define label_stabbr RI "Rhode Island", add
label define label_stabbr SC "South Carolina", add
label define label_stabbr SD "South Dakota", add
label define label_stabbr TN "Tennessee", add
label define label_stabbr TX "Texas", add
label define label_stabbr UT "Utah", add
label define label_stabbr VT "Vermont", add
label define label_stabbr VA "Virginia", add
label define label_stabbr WA "Washington", add
label define label_stabbr WV "West Virginia", add
label define label_stabbr WI "Wisconsin", add
label define label_stabbr WY "Wyoming", add
label define label_stabbr AS "American Samoa", add
label define label_stabbr FM "Federated States of Micronesia", add
label define label_stabbr GU "Guam", add
label define label_stabbr MH "Marshall Islands", add
label define label_stabbr MP "Northern Marianas", add
label define label_stabbr PW "Palau", add
label define label_stabbr PR "Puerto Rico", add
label define label_stabbr VI "Virgin Islands", add
label define label_locale 11 "City: Large"
label define label_locale 41 "Rural: Fringe", add
label define label_locale -3 "{Not available}", add
label define label_c21basic 1 "Associate^s Colleges: High Transfer-High Traditional"
label define label_c21basic 15 "Doctoral Universities: Very High Research Activity", add
label define label_c21basic 33 "Tribal Colleges", add
label define label_c21basic -2 "Not applicable, not in Carnegie universe (not accredited or nondegree-granting)", add
label define label_c21szset 2 "Two-year, small", add
label define label_c21szset 11 "Four-year, small, highly residential", add
label define label_c21szset 18 "Exclusively graduate/professional", add
label define label_c21szset -2 "Not applicable, not in Carnegie universe (not accredited or nondegree-granting)", add
label variable obereg     "Bureau of Economic Analysis (BEA) regions"

