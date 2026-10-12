// Built-in suggestions for the "City" field: no third-party service, nothing leaves the device.
// Free text is always allowed; this list only helps people type less.
const INDIA: Record<string, string[]> = {
  "Andhra Pradesh": ["Visakhapatnam", "Vijayawada", "Guntur", "Nellore", "Tirupati", "Kurnool", "Rajahmundry", "Kakinada", "Anantapur", "Kadapa"],
  "Arunachal Pradesh": ["Itanagar"],
  "Assam": ["Guwahati", "Silchar", "Dibrugarh", "Jorhat", "Tezpur"],
  "Bihar": ["Patna", "Gaya", "Bhagalpur", "Muzaffarpur", "Darbhanga", "Purnia", "Arrah"],
  "Chandigarh": ["Chandigarh"],
  "Chhattisgarh": ["Raipur", "Bhilai", "Bilaspur", "Korba", "Durg"],
  "Delhi": ["New Delhi", "Delhi"],
  "Goa": ["Panaji", "Margao", "Vasco da Gama", "Mapusa"],
  "Gujarat": ["Ahmedabad", "Surat", "Vadodara", "Rajkot", "Bhavnagar", "Jamnagar", "Gandhinagar", "Junagadh", "Anand", "Bharuch", "Navsari"],
  "Haryana": ["Gurugram", "Faridabad", "Panipat", "Ambala", "Karnal", "Hisar", "Rohtak", "Sonipat", "Panchkula"],
  "Himachal Pradesh": ["Shimla", "Dharamshala", "Solan", "Mandi", "Kullu"],
  "Jammu and Kashmir": ["Srinagar", "Jammu"],
  "Jharkhand": ["Ranchi", "Jamshedpur", "Dhanbad", "Bokaro", "Hazaribagh"],
  "Karnataka": ["Bengaluru", "Mysuru", "Mangaluru", "Hubballi", "Belagavi", "Kalaburagi", "Davanagere", "Ballari", "Shivamogga", "Udupi", "Tumakuru"],
  "Kerala": ["Thiruvananthapuram", "Kochi", "Kozhikode", "Thrissur", "Kollam", "Kannur", "Alappuzha", "Kottayam", "Palakkad", "Malappuram"],
  "Madhya Pradesh": ["Bhopal", "Indore", "Jabalpur", "Gwalior", "Ujjain", "Sagar", "Rewa", "Satna"],
  "Maharashtra": ["Mumbai", "Pune", "Nagpur", "Nashik", "Thane", "Navi Mumbai", "Chhatrapati Sambhajinagar", "Solapur", "Kolhapur", "Amravati", "Nanded", "Sangli", "Satara", "Latur", "Akola", "Jalgaon"],
  "Manipur": ["Imphal"],
  "Meghalaya": ["Shillong"],
  "Mizoram": ["Aizawl"],
  "Nagaland": ["Kohima", "Dimapur"],
  "Odisha": ["Bhubaneswar", "Cuttack", "Rourkela", "Berhampur", "Sambalpur", "Puri"],
  "Puducherry": ["Puducherry"],
  "Punjab": ["Ludhiana", "Amritsar", "Jalandhar", "Patiala", "Bathinda", "Mohali"],
  "Rajasthan": ["Jaipur", "Jodhpur", "Udaipur", "Kota", "Ajmer", "Bikaner", "Alwar", "Bhilwara", "Sikar"],
  "Sikkim": ["Gangtok"],
  "Tamil Nadu": ["Chennai", "Coimbatore", "Madurai", "Tiruchirappalli", "Salem", "Tirunelveli", "Erode", "Vellore", "Thoothukudi", "Thanjavur", "Tiruppur", "Hosur"],
  "Telangana": ["Hyderabad", "Warangal", "Nizamabad", "Karimnagar", "Khammam", "Secunderabad"],
  "Tripura": ["Agartala"],
  "Uttar Pradesh": ["Lucknow", "Kanpur", "Varanasi", "Agra", "Prayagraj", "Ghaziabad", "Noida", "Meerut", "Bareilly", "Aligarh", "Moradabad", "Gorakhpur", "Jhansi", "Mathura", "Saharanpur", "Firozabad", "Ayodhya", "Greater Noida"],
  "Uttarakhand": ["Dehradun", "Haridwar", "Haldwani", "Roorkee", "Rishikesh"],
  "West Bengal": ["Kolkata", "Howrah", "Durgapur", "Asansol", "Siliguri", "Kharagpur"],
};

// Where many NRI families live. "City, Country" (the parent may live there too).
const ABROAD = [
  "New York, USA", "Los Angeles, USA", "Chicago, USA", "Houston, USA", "San Francisco, USA", "San Jose, USA", "Seattle, USA", "Dallas, USA",
  "Austin, USA", "Boston, USA", "Atlanta, USA", "Washington DC, USA", "Edison, USA", "Jersey City, USA", "Phoenix, USA", "Detroit, USA",
  "London, UK", "Birmingham, UK", "Manchester, UK", "Leicester, UK", "Leeds, UK", "Glasgow, UK", "Edinburgh, UK",
  "Dubai, UAE", "Abu Dhabi, UAE", "Sharjah, UAE",
  "Toronto, Canada", "Vancouver, Canada", "Calgary, Canada", "Ottawa, Canada", "Mississauga, Canada", "Brampton, Canada",
  "Sydney, Australia", "Melbourne, Australia", "Brisbane, Australia", "Perth, Australia",
  "Singapore", "Doha, Qatar", "Muscat, Oman", "Riyadh, Saudi Arabia", "Kuwait City, Kuwait", "Manama, Bahrain",
  "Auckland, New Zealand", "Berlin, Germany", "Dublin, Ireland", "Amsterdam, Netherlands",
];

export const CITIES: string[] = [
  ...Object.entries(INDIA).flatMap(([state, cities]) => cities.map((city) => `${city}, ${state}`)),
  ...ABROAD,
].sort((a, b) => a.localeCompare(b));
